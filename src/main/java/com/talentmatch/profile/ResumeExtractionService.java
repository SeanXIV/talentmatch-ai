package com.talentmatch.profile;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.talentmatch.ai.FailureKind;
import com.talentmatch.ai.LocalModelGate;
import com.talentmatch.config.ProfileConfig;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.FinishReason;
import dev.langchain4j.model.output.TokenUsage;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.Timer;
import java.io.InterruptedIOException;
import java.nio.channels.ClosedByInterruptException;
import java.sql.SQLException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

/**
 * Turns an uploaded CV into a draft profile on the single-threaded profile executor: claims the
 * row (PENDING → RUNNING), calls the model once with the hand-built nullable schema
 * ({@link ProfileJsonSchema}), normalizes and grounds the result, then stores SUCCEEDED (draft +
 * warnings) or FAILED (reason). Never throws to its caller.
 *
 * <p>When the provider is a local model ({@link LocalModelGate} present) the call holds the
 * model exclusively, so explanations get AI_BUSY instead of timing out behind it.
 *
 * <p>A shutdown interrupts the worker: the CV is then left RUNNING (not FAILED), and
 * {@link ProfileRecovery} re-queues it on the next start. Each claim counts an attempt; after
 * {@link ResumeRepository#MAX_ATTEMPTS} the CV is FAILED/TOO_MANY_ATTEMPTS.
 *
 * <p>Logs one INFO line per extraction with outcome, latency and token counts. Never logs CV
 * content: exceptions are logged by class name (plus SQLState), never by message, because model
 * parse errors quote the model's output and PostgreSQL errors can quote the failing row.
 */
@Service
public class ResumeExtractionService {

    private static final Logger log = LoggerFactory.getLogger(ResumeExtractionService.class);

    static final String TIMER = "talentmatch.ai.extractions";
    /** Prompt + output within this many tokens of num_ctx means Ollama probably cut the prompt. */
    static final int CONTEXT_MARGIN_TOKENS = 32;
    private static final int MAX_CAUSE_DEPTH = 16;

    private final ResumeRepository repository;
    private final ObjectProvider<ResumeExtractionModel> model;
    private final ObjectProvider<LocalModelGate> gate;
    private final Executor executor;
    private final ProfileProperties properties;
    private final Clock clock;
    private final MeterRegistry meterRegistry;

    public ResumeExtractionService(ResumeRepository repository, ObjectProvider<ResumeExtractionModel> model,
                                   ObjectProvider<LocalModelGate> gate,
                                   @Qualifier(ProfileConfig.PROFILE_EXECUTOR) Executor executor,
                                   ProfileProperties properties, Clock clock,
                                   ObjectProvider<MeterRegistry> meterRegistry) {
        this.repository = repository;
        this.model = model;
        this.gate = gate;
        this.executor = executor;
        this.properties = properties;
        this.clock = clock;
        MeterRegistry registry = meterRegistry.getIfAvailable();
        this.meterRegistry = registry == null ? Metrics.globalRegistry : registry;
    }

    /** Queues the CV for extraction; a full queue marks it FAILED/QUEUE_FULL. */
    public void enqueue(UUID resumeId) {
        try {
            executor.execute(() -> run(resumeId));
        } catch (RejectedExecutionException e) { // includes Spring's TaskRejectedException
            log.warn("Profile extraction queue is full; CV {} not queued", resumeId);
            repository.fail(resumeId, ExtractionFailure.QUEUE_FULL, null, clock.instant());
        }
    }

    /** Runs one extraction on the calling thread. Never throws. */
    void run(UUID resumeId) {
        try {
            if (!repository.claim(resumeId, clock.instant())) {
                if (repository.failExhausted(resumeId, clock.instant())) {
                    log.warn("CV extraction resume={} outcome=too_many_attempts (interrupted {} times)", resumeId,
                            ResumeRepository.MAX_ATTEMPTS);
                }
                return; // already taken, finished, deleted, or out of attempts
            }
            ResumeExtractionModel m = model.getIfAvailable();
            if (m == null) {
                repository.fail(resumeId, ExtractionFailure.AI_DISABLED, null, clock.instant());
                log.info("CV extraction resume={} outcome=ai_disabled", resumeId);
                return;
            }
            if (!m.local() && !properties.allowRemoteExtraction()) {
                // the CV would leave the machine: only with the owner's explicit opt-in
                repository.fail(resumeId, ExtractionFailure.REMOTE_EXTRACTION_DISABLED, m.label(), clock.instant());
                log.info("CV extraction resume={} model={} outcome=remote_extraction_disabled", resumeId, m.label());
                return;
            }
            extract(resumeId, m);
        } catch (RuntimeException e) {
            if (interrupted(e)) {
                logInterrupted(resumeId);
                return;
            }
            log.error("Unexpected error while extracting CV {} ({}); marking it failed", resumeId, describe(e));
            safeFail(resumeId, ExtractionFailure.PROVIDER_ERROR, null);
        }
    }

    private void extract(UUID resumeId, ResumeExtractionModel m) {
        Optional<String> stored = repository.findText(resumeId);
        if (stored.isEmpty()) {
            return; // deleted meanwhile
        }
        List<ProfileWarning> warnings = new ArrayList<>();
        String text = stored.get();
        if (text.length() > properties.maxTextChars()) {
            warnings.add(new ProfileWarning(null, null, "Your CV is long, so only its first "
                    + properties.maxTextChars() + " characters were read. Check that later sections are complete."));
            text = ProfileNormalizer.cut(text, properties.maxTextChars());
        }

        LocalModelGate localGate = gate.getIfAvailable();
        if (localGate != null) {
            try {
                localGate.acquireExclusive();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                logInterrupted(resumeId);
                return;
            }
        }
        long t0 = System.nanoTime();
        TokenUsage usage = null;
        String outcome;
        try {
            ChatResponse response = m.chatModel().chat(request(text));
            usage = response == null ? null : response.tokenUsage();
            outcome = finish(resumeId, m, response, text, warnings);
        } catch (RuntimeException e) {
            if (interrupted(e)) {
                logInterrupted(resumeId);
                return;
            }
            FailureKind kind = FailureKind.classify(e);
            log.debug("CV extraction call failed ({}: {})", kind, describe(e));
            outcome = kind.tag();
            safeFail(resumeId, ExtractionFailure.of(kind), m.label());
        } finally {
            if (localGate != null) {
                localGate.releaseExclusive();
            }
        }
        long latencyMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
        try {
            Timer.builder(TIMER).tag("outcome", outcome).register(meterRegistry)
                    .record(latencyMs, TimeUnit.MILLISECONDS);
        } catch (RuntimeException e) {
            log.debug("Could not record extraction metrics ({})", e.getClass().getSimpleName());
        }
        log.info("CV extraction resume={} model={} outcome={} latencyMs={} inputTokens={} outputTokens={} warnings={}",
                resumeId, m.label(), outcome, latencyMs, usage == null ? null : usage.inputTokenCount(),
                usage == null ? null : usage.outputTokenCount(), warnings.size());
    }

    static ChatRequest request(String cvText) {
        return ChatRequest.builder()
                .messages(SystemMessage.from(ResumeExtractionPrompts.SYSTEM),
                        UserMessage.from(ResumeExtractionPrompts.userMessage(cvText)))
                .responseFormat(ProfileJsonSchema.responseFormat())
                .build();
    }

    /** Checks, parses, normalizes, grounds and stores the answer. Returns the outcome tag. */
    private String finish(UUID resumeId, ResumeExtractionModel m, ChatResponse response, String text,
                          List<ProfileWarning> warnings) {
        Optional<ExtractionFailure> problem = check(response, m.contextTokens());
        if (problem.isPresent()) {
            safeFail(resumeId, problem.get(), m.label());
            return problem.get().name().toLowerCase(Locale.ROOT);
        }
        ProfileDocument raw;
        try {
            raw = ProfileJson.modelOutput(response.aiMessage().text());
        } catch (JsonProcessingException e) {
            log.debug("CV extraction answer was not valid profile JSON ({})", e.getClass().getSimpleName());
            safeFail(resumeId, ExtractionFailure.INVALID_OUTPUT, m.label());
            return "invalid_output";
        }
        ProfileNormalizer.Result normalized = ProfileNormalizer.normalize(raw, ProfileNormalizer.Mode.LENIENT, clock);
        if (isEmpty(normalized.document())) {
            safeFail(resumeId, ExtractionFailure.INVALID_OUTPUT, m.label());
            return "invalid_output";
        }
        for (ProfileNormalizer.Issue issue : normalized.issues()) {
            warnings.add(new ProfileWarning(issue.path(), issue.value(), issue.message()));
        }
        ResumeGrounding.Result grounded = ResumeGrounding.apply(normalized.document(), text, clock);
        warnings.addAll(grounded.warnings());
        boolean saved = repository.succeed(resumeId, m.label(), ProfileJson.write(grounded.document()),
                ProfileJson.write(warnings), clock.instant());
        return saved ? "success" : "discarded";
    }

    /**
     * Context overflow first (a cut prompt can still end with STOP or LENGTH), then the finish
     * reason (LENGTH means the JSON was cut off; anything else but STOP is a refusal, which
     * often comes with no text at all, e.g. Claude's {@code stop_reason: refusal}), and only then
     * a missing or blank answer.
     */
    static Optional<ExtractionFailure> check(ChatResponse response, Integer contextTokens) {
        if (response == null) {
            return Optional.of(ExtractionFailure.INVALID_OUTPUT);
        }
        TokenUsage usage = response.tokenUsage();
        if (contextTokens != null && usage != null && usage.inputTokenCount() != null
                && usage.outputTokenCount() != null
                && usage.inputTokenCount() + usage.outputTokenCount() >= contextTokens - CONTEXT_MARGIN_TOKENS) {
            return Optional.of(ExtractionFailure.CONTEXT_OVERFLOW);
        }
        FinishReason finish = response.finishReason();
        if (finish == FinishReason.LENGTH) {
            return Optional.of(ExtractionFailure.INVALID_OUTPUT);
        }
        if (finish != null && finish != FinishReason.STOP) {
            return Optional.of(ExtractionFailure.REFUSED);
        }
        if (response.aiMessage() == null || response.aiMessage().text() == null
                || response.aiMessage().text().isBlank()) {
            return Optional.of(ExtractionFailure.INVALID_OUTPUT);
        }
        return Optional.empty();
    }

    /** Nothing useful came back: no name, no roles, no skills, no education. */
    private static boolean isEmpty(ProfileDocument d) {
        return d.fullName() == null && d.experience().isEmpty() && d.skills().isEmpty() && d.education().isEmpty()
                && d.projects().isEmpty();
    }

    /** A shutdown interrupted the worker (flag set, or an interrupt in the cause chain). */
    static boolean interrupted(Throwable failure) {
        if (Thread.currentThread().isInterrupted()) {
            return true;
        }
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        int depth = 0;
        for (Throwable t = failure; t != null && depth < MAX_CAUSE_DEPTH && seen.add(t); t = t.getCause(), depth++) {
            if (t instanceof InterruptedException || t instanceof ClosedByInterruptException
                    || (t instanceof InterruptedIOException && !(t instanceof java.net.SocketTimeoutException))) {
                return true;
            }
        }
        return false;
    }

    private static void logInterrupted(UUID resumeId) {
        log.info("CV extraction resume={} interrupted by shutdown; will re-run on next start", resumeId);
    }

    /** Exception class names along the cause chain, plus the SQLState of a database error. Never messages. */
    static String describe(Throwable failure) {
        StringBuilder sb = new StringBuilder();
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        int depth = 0;
        for (Throwable t = failure; t != null && depth < MAX_CAUSE_DEPTH && seen.add(t); t = t.getCause(), depth++) {
            if (!sb.isEmpty()) {
                sb.append(" <- ");
            }
            sb.append(t.getClass().getSimpleName());
            if (t instanceof SQLException sql && sql.getSQLState() != null) {
                sb.append("[SQLState ").append(sql.getSQLState()).append(']');
            }
        }
        return sb.toString();
    }

    private void safeFail(UUID resumeId, ExtractionFailure reason, String modelLabel) {
        try {
            repository.fail(resumeId, reason, modelLabel, clock.instant());
        } catch (RuntimeException e) {
            log.error("Could not mark CV {} as failed ({}); it will be retried after a restart", resumeId,
                    describe(e));
        }
    }
}
