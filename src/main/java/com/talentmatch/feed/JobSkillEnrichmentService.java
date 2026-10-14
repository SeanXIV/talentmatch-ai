package com.talentmatch.feed;

import com.talentmatch.ai.FailureKind;
import com.talentmatch.ai.Interrupts;
import com.talentmatch.ai.LocalModelGate;
import com.talentmatch.feed.skills.EnrichmentFailure;
import com.talentmatch.feed.skills.JobSkillExtractionModel;
import com.talentmatch.feed.skills.JobSkillJsonSchema;
import com.talentmatch.feed.skills.JobSkillPrompts;
import com.talentmatch.feed.skills.JobSkillValidator;
import com.talentmatch.feed.skills.SkillDictionary;
import com.talentmatch.feed.skills.SkillsProperties;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.FinishReason;
import dev.langchain4j.model.output.TokenUsage;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.Timer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

/**
 * Skill extraction stage 2 (§4.7, decision f): the AI reads the postings the dictionary already
 * processed and adds the skills it finds, checked by {@link JobSkillValidator}. It never blocks the
 * first notification (stage 1 is the dictionary) and never creates a skill.
 *
 * <p><b>One run</b> ({@link #enrichDue()}), one job at a time:
 * <ul>
 *   <li>PENDING jobs without a description → SKIPPED (nothing to read; a later description resets them).</li>
 *   <li>PENDING jobs out of attempts (interrupted or failed repeatedly) → FAILED/TOO_MANY_ATTEMPTS.</li>
 *   <li>No model (AI off) or {@code enrichment.enabled=false} → every PENDING job SKIPPED (dictionary
 *       only). Not an error; skipped open jobs get their turn after a restart with AI on.</li>
 *   <li>Paused after a timeout or provider error → nothing until {@code failure-backoff} has passed.</li>
 *   <li>Hourly cap ({@code max-per-hour}, sliding window) checked before claiming: nothing is claimed
 *       while it is reached (as the notification cap, step 8 D3).</li>
 *   <li>Claim (PENDING → RUNNING, one attempt counted). A local model busy with a CV extraction
 *       ({@link LocalModelGate#tryAcquireShared()} false) → back to PENDING, attempt given back, retried
 *       after {@code busy-retry}; the run stops.</li>
 *   <li>Call, check the answer as CV extraction does, validate → SUCCEEDED with {@code ai_skills} and
 *       {@code ai_suggestions}; the job goes back to the processor to be re-scored.</li>
 *   <li>Failure: TIMEOUT / PROVIDER_ERROR → the whole stage pauses {@code failure-backoff} and the job
 *       waits as long with its attempt given back (the model is probably down; the pause bounds the
 *       retries, so a provider fault never makes a job FAILED; the explanation circuit breaker is not
 *       fed). REFUSED / INVALID_OUTPUT → retried after the backoff without a pause; the
 *       {@code max-attempts}-th is FAILED. A prompt that didn't fit the context → FAILED/INVALID_OUTPUT
 *       at once (outcome {@code context_overflow}).</li>
 *   <li>Only the model call is classified as a model failure. A database error while storing the
 *       result → back to PENDING (attempt given back, after {@code busy-retry}), no pause, the run ends
 *       (outcome {@code db_error}). A failed release or FAILED write is logged and left to
 *       {@link FeedRecovery}.</li>
 *   <li>A shutdown interrupt leaves the row RUNNING; {@link FeedRecovery} resets it.</li>
 * </ul>
 *
 * <p><b>Threads.</b> One {@code feed-ai-} thread, woken after processor runs, after startup recovery and
 * by a sweep ({@code enrichment.sweep}); background runs only while the feed scheduler runs. Tests call
 * {@link #enrichDue()}. One run at a time.
 *
 * <p>One INFO line per call: job, model, outcome, latency, tokens and counts. Never the posting text or
 * the answer.
 */
@Component
public class JobSkillEnrichmentService {

    private static final Logger log = LoggerFactory.getLogger(JobSkillEnrichmentService.class);

    static final String TIMER = "talentmatch.feed.enrichments";
    /** Prompt + output within this many tokens of num_ctx means Ollama probably cut the prompt. */
    static final int CONTEXT_MARGIN_TOKENS = 32;
    static final Duration CAP_WINDOW = Duration.ofHours(1);

    private final FeedEnrichmentRepository repository;
    private final ObjectProvider<JobSkillExtractionModel> model;
    private final ObjectProvider<LocalModelGate> gate;
    private final SkillDictionary dictionary;
    private final SkillsProperties skills;
    private final SkillsProperties.Enrichment settings;
    private final FeedProperties feedProperties;
    private final ObjectProvider<FeedProcessor> processor;
    private final ThreadPoolTaskExecutor executor;
    private final Clock clock;
    private final MeterRegistry meterRegistry;

    private final ReentrantLock runLock = new ReentrantLock();
    /** Start times of the calls in the last hour; guarded by {@link #runLock}. */
    private final Deque<Instant> calls = new ArrayDeque<>();
    private volatile Instant pausedUntil;
    /** Set when a run stopped at the hourly cap; read by the status endpoint. */
    private volatile Instant capReachedUntil;

    public JobSkillEnrichmentService(FeedEnrichmentRepository repository,
                                     ObjectProvider<JobSkillExtractionModel> model,
                                     ObjectProvider<LocalModelGate> gate, SkillDictionary dictionary,
                                     SkillsProperties skills, FeedProperties feedProperties,
                                     ObjectProvider<FeedProcessor> processor,
                                     @Qualifier(FeedConfig.AI_EXECUTOR) ThreadPoolTaskExecutor executor, Clock clock,
                                     ObjectProvider<MeterRegistry> meterRegistry) {
        this.repository = repository;
        this.model = model;
        this.gate = gate;
        this.dictionary = dictionary;
        this.skills = skills;
        this.settings = skills.enrichment();
        this.feedProperties = feedProperties;
        this.processor = processor;
        this.executor = executor;
        this.clock = clock;
        MeterRegistry registry = meterRegistry.getIfAvailable();
        this.meterRegistry = registry == null ? Metrics.globalRegistry : registry;
    }

    /**
     * What one run did.
     *
     * @param busy          another run was in progress; this one did nothing
     * @param skipped       PENDING jobs marked SKIPPED (no description, or AI is off)
     * @param retried       jobs put back to PENDING (failure, pause, model busy or database error)
     * @param modelBusy     the local model was busy with a CV extraction
     * @param pausedUntil   the stage is paused after a provider failure (null when not)
     * @param deferredUntil the hourly cap stopped the run until then (null when not)
     */
    public record Report(boolean busy, int succeeded, int failed, int retried, int skipped, boolean modelBusy,
                         Instant pausedUntil, Instant deferredUntil) {

        static Report ofBusy() {
            return new Report(true, 0, 0, 0, 0, false, null, null);
        }
    }

    /** The AI stage can run: a model exists and enrichment is enabled. */
    public boolean aiEnabled() {
        return settings.enabled() && model.getIfAvailable() != null;
    }

    /** True while a CV extraction holds the local model. */
    public boolean modelBusy() {
        LocalModelGate g = gate.getIfAvailable();
        return g != null && g.exclusiveHeld();
    }

    /** The end of a pause after a provider fault, or null when the stage isn't paused. */
    public Instant pausedUntil() {
        Instant p = pausedUntil;
        return p != null && p.isAfter(clock.instant()) ? p : null;
    }

    /** When the hourly cap last stopped a run, the time it frees up; null once that time has passed. */
    public Instant capReachedUntil() {
        Instant c = capReachedUntil;
        return c != null && c.isAfter(clock.instant()) ? c : null;
    }

    /** Starts a background run, unless background work is off (the scheduler is off). Never blocks. */
    public void wake() {
        if (!feedProperties.schedulerRunning()) {
            return;
        }
        try {
            executor.execute(this::backgroundRun);
        } catch (TaskRejectedException e) {
            log.debug("Feed enrichment wake-up rejected ({})", e.getClass().getName());
        }
    }

    /** One run on the calling thread (tests, background thread). Never throws for model trouble. */
    public Report enrichDue() {
        if (!runLock.tryLock()) {
            return Report.ofBusy();
        }
        try {
            return run();
        } finally {
            runLock.unlock();
        }
    }

    private Report run() {
        if (!feedProperties.enabled()) {
            return new Report(false, 0, 0, 0, 0, false, null, null);
        }
        int noDescription = repository.skipNoDescription();
        if (noDescription > 0) {
            log.info("Feed enrichment: {} job(s) without a description keep their dictionary skills only (SKIPPED)",
                    noDescription);
        }
        int exhausted = repository.failExhausted(settings.maxAttempts());
        if (exhausted > 0) {
            log.warn("Feed enrichment: {} job(s) FAILED/TOO_MANY_ATTEMPTS (interrupted {} times)", exhausted,
                    settings.maxAttempts());
        }
        JobSkillExtractionModel m = model.getIfAvailable();
        if (m == null || !settings.enabled()) {
            int skipped = repository.skipPending();
            if (skipped > 0) {
                log.info("Feed enrichment: AI is off; {} job(s) keep their dictionary skills only (SKIPPED)", skipped);
            }
            return new Report(false, 0, exhausted, 0, noDescription + skipped, false, null, null);
        }
        Instant now = clock.instant();
        Instant paused = pausedUntil;
        if (paused != null && now.isBefore(paused)) {
            return new Report(false, 0, exhausted, 0, noDescription, false, paused, null);
        }
        pausedUntil = null;

        int succeeded = 0;
        int failed = exhausted;
        int retried = 0;
        SkillDictionary.Snapshot vocabulary = dictionary.refresh();
        while (!Thread.currentThread().isInterrupted()) {
            now = clock.instant();
            Instant deferred = capEnd(now);
            if (deferred != null) {
                capReachedUntil = deferred;
                return new Report(false, succeeded, failed, retried, noDescription, false, null, deferred);
            }
            Optional<FeedEnrichmentRepository.Task> claimed = repository.claim(settings.maxAttempts(), now);
            if (claimed.isEmpty()) {
                break;
            }
            Outcome outcome = enrichOne(claimed.get(), m, vocabulary);
            switch (outcome) {
                case SUCCEEDED -> succeeded++;
                case FAILED -> failed++;
                case RETRY -> retried++;
                case DISCARDED -> { }
                case MODEL_BUSY -> {
                    return new Report(false, succeeded, failed, retried + 1, noDescription, true, null, null);
                }
                case PAUSED -> {
                    return new Report(false, succeeded, failed, retried + 1, noDescription, false, pausedUntil, null);
                }
                case INTERRUPTED -> {
                    return new Report(false, succeeded, failed, retried, noDescription, false, null, null);
                }
                case DB_ERROR -> {           // the database is in trouble: stop, the next sweep retries
                    return new Report(false, succeeded, failed, retried + 1, noDescription, false, null, null);
                }
            }
        }
        return new Report(false, succeeded, failed, retried, noDescription, false, null, null);
    }

    private enum Outcome {
        SUCCEEDED, FAILED, RETRY, DISCARDED, MODEL_BUSY, PAUSED, INTERRUPTED, DB_ERROR
    }

    /** The oldest call's hour end when {@code max-per-hour} calls were made in the last hour, else null. */
    private Instant capEnd(Instant now) {
        while (!calls.isEmpty() && !calls.peekFirst().isAfter(now.minus(CAP_WINDOW))) {
            calls.pollFirst();
        }
        return calls.size() >= settings.maxPerHour() ? calls.peekFirst().plus(CAP_WINDOW) : null;
    }

    /** What one call ended as: the run's outcome, the timer/log tag and the validator's counts. */
    private record Finish(Outcome outcome, String tag, int accepted, int suggestions, int ungrounded) {

        Finish(Outcome outcome, String tag) {
            this(outcome, tag, 0, 0, 0);
        }
    }

    private Outcome enrichOne(FeedEnrichmentRepository.Task task, JobSkillExtractionModel m,
                              SkillDictionary.Snapshot vocabulary) {
        LocalModelGate localGate = m.local() ? gate.getIfAvailable() : null;
        if (localGate != null && !localGate.tryAcquireShared()) {
            safeRelease(task, clock.instant().plus(settings.busyRetry()), true);
            log.info("Feed enrichment job={} model={} outcome=model_busy: a CV extraction holds the model; retrying "
                    + "in {}s", task.jobId(), m.label(), settings.busyRetry().toSeconds());
            return Outcome.MODEL_BUSY;
        }
        calls.addLast(clock.instant());
        String posting = JobSkillPrompts.postingText(task.title(), task.description(), skills.maxDescriptionChars());
        long t0 = System.nanoTime();
        ChatResponse response = null;
        RuntimeException callFailure = null;
        // only the model call is classified by FailureKind; the gate is released before any database write
        try {
            response = m.chatModel().chat(request(task.company(), posting));
        } catch (RuntimeException e) {
            callFailure = e;
        } finally {
            if (localGate != null) {
                localGate.releaseShared();
            }
        }
        if (callFailure != null) {
            if (Interrupts.interrupted(callFailure)) {
                log.info("Feed enrichment job={} interrupted by shutdown; will re-run on next start", task.jobId());
                return Outcome.INTERRUPTED;
            }
            FailureKind kind = FailureKind.classify(callFailure);
            log.debug("Feed enrichment call failed ({}: {})", kind, SourcePoller.describe(callFailure));
            Outcome outcome = kind.providerFault() ? pause(task) : retryOrFail(task, EnrichmentFailure.of(kind), m);
            record(task, m, new Finish(outcome, kind.tag()), t0, null);
            return outcome;
        }
        Finish finish;
        try {
            finish = finish(task, m, response, posting, vocabulary);
        } catch (DataAccessException e) {
            // the answer may be fine: not the model's fault, so no pause; the attempt is given back
            log.warn("Feed enrichment job={} could not store the result ({}); the run ends", task.jobId(),
                    SourcePoller.describe(e));
            safeRelease(task, clock.instant().plus(settings.busyRetry()), true);
            finish = new Finish(Outcome.DB_ERROR, "db_error");
        }
        record(task, m, finish, t0, response == null ? null : response.tokenUsage());
        return finish.outcome();
    }

    /** Checks, validates and stores an answer. Database errors from the finishing write propagate. */
    private Finish finish(FeedEnrichmentRepository.Task task, JobSkillExtractionModel m, ChatResponse response,
                          String posting, SkillDictionary.Snapshot vocabulary) {
        Optional<AnswerProblem> problem = check(response, m.contextTokens());
        if (problem.isPresent()) {
            AnswerProblem p = problem.get();
            return new Finish(p == AnswerProblem.CONTEXT_OVERFLOW
                    ? safeFail(task, p.failure(), m)             // the same prompt would be cut again
                    : retryOrFail(task, p.failure(), m), p.tag());
        }
        JobSkillValidator.Result result;
        try {
            result = JobSkillValidator.validate(response.aiMessage().text(), posting, vocabulary);
        } catch (JobSkillValidator.InvalidOutputException e) {
            log.debug("Feed enrichment answer rejected ({})", e.getMessage());
            return new Finish(retryOrFail(task, EnrichmentFailure.INVALID_OUTPUT, m), "invalid_output");
        }
        boolean saved = repository.succeed(task, FeedJobJson.write(result.accepted()),
                FeedJobJson.write(result.suggestions()), m.label(), clock.instant());
        if (saved) {
            FeedProcessor p = processor.getIfAvailable();
            if (p != null) {
                p.wake();
            }
        }
        return new Finish(saved ? Outcome.SUCCEEDED : Outcome.DISCARDED, saved ? "success" : "discarded",
                result.accepted().size(), result.suggestions().size(), result.ungrounded());
    }

    private void record(FeedEnrichmentRepository.Task task, JobSkillExtractionModel m, Finish f, long t0,
                        TokenUsage usage) {
        long latencyMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
        try {
            Timer.builder(TIMER).tag("outcome", f.tag()).register(meterRegistry)
                    .record(latencyMs, TimeUnit.MILLISECONDS);
        } catch (RuntimeException e) {
            log.debug("Could not record enrichment metrics ({})", e.getClass().getSimpleName());
        }
        log.info("Feed enrichment job={} model={} outcome={} attempt={} latencyMs={} inputTokens={} outputTokens={} "
                        + "accepted={} suggestions={} ungrounded={}", task.jobId(), m.label(), f.tag(), task.attempts(),
                latencyMs, usage == null ? null : usage.inputTokenCount(),
                usage == null ? null : usage.outputTokenCount(), f.accepted(), f.suggestions(), f.ungrounded());
    }

    /**
     * A provider fault (TIMEOUT / PROVIDER_ERROR): the model is probably down, so the whole stage pauses
     * {@code failure-backoff} and the job waits as long. The attempt is given back: the pause bounds the
     * retries, and a job never fails because the provider was down.
     */
    private Outcome pause(FeedEnrichmentRepository.Task task) {
        Instant until = clock.instant().plus(settings.failureBackoff());
        pausedUntil = until;
        safeRelease(task, until, true);
        return Outcome.PAUSED;
    }

    /** REFUSED / INVALID_OUTPUT: the {@code max-attempts}-th is final; earlier ones wait {@code failure-backoff}. */
    private Outcome retryOrFail(FeedEnrichmentRepository.Task task, EnrichmentFailure failure,
                                JobSkillExtractionModel m) {
        if (task.attempts() >= settings.maxAttempts()) {
            return safeFail(task, failure, m);
        }
        safeRelease(task, clock.instant().plus(settings.failureBackoff()), false);
        return Outcome.RETRY;
    }

    /** RUNNING → FAILED; a database error leaves the row RUNNING for {@link FeedRecovery} (logged, never thrown). */
    private Outcome safeFail(FeedEnrichmentRepository.Task task, EnrichmentFailure failure,
                             JobSkillExtractionModel m) {
        try {
            repository.fail(task.jobId(), failure, m.label());
        } catch (RuntimeException e) {
            log.error("Feed enrichment job={} could not be marked FAILED ({}); it is re-queued after a restart",
                    task.jobId(), SourcePoller.describe(e));
        }
        return Outcome.FAILED;
    }

    /** Back to PENDING; a database error leaves the row RUNNING for {@link FeedRecovery} (logged, never thrown). */
    private void safeRelease(FeedEnrichmentRepository.Task task, Instant notBefore, boolean uncount) {
        try {
            repository.release(task.jobId(), notBefore, uncount);
        } catch (RuntimeException e) {
            log.error("Feed enrichment job={} could not be put back to PENDING ({}); it is re-queued after a restart",
                    task.jobId(), SourcePoller.describe(e));
        }
    }

    static ChatRequest request(String company, String postingText) {
        return ChatRequest.builder()
                .messages(SystemMessage.from(JobSkillPrompts.SYSTEM),
                        UserMessage.from(JobSkillPrompts.userMessage(company, postingText)))
                .responseFormat(JobSkillJsonSchema.responseFormat())
                .build();
    }

    /** What is wrong with an answer before it is parsed: the stored failure and the timer/log tag. */
    enum AnswerProblem {
        /** The prompt was probably cut: FAILED at once, the same prompt would be cut again. */
        CONTEXT_OVERFLOW(EnrichmentFailure.INVALID_OUTPUT, "context_overflow"),
        INVALID_OUTPUT(EnrichmentFailure.INVALID_OUTPUT, "invalid_output"),
        REFUSED(EnrichmentFailure.REFUSED, "refused");

        private final EnrichmentFailure failure;
        private final String tag;

        AnswerProblem(EnrichmentFailure failure, String tag) {
            this.failure = failure;
            this.tag = tag;
        }

        EnrichmentFailure failure() {
            return failure;
        }

        String tag() {
            return tag;
        }
    }

    /**
     * As {@code ResumeExtractionService.check}: a cut-off prompt first (CONTEXT_OVERFLOW), then LENGTH
     * (INVALID_OUTPUT), any other non-STOP finish (REFUSED), and a missing or blank answer (INVALID_OUTPUT).
     */
    static Optional<AnswerProblem> check(ChatResponse response, Integer contextTokens) {
        if (response == null) {
            return Optional.of(AnswerProblem.INVALID_OUTPUT);
        }
        if (overflow(response, contextTokens)) {
            return Optional.of(AnswerProblem.CONTEXT_OVERFLOW);
        }
        FinishReason finish = response.finishReason();
        if (finish == FinishReason.LENGTH) {
            return Optional.of(AnswerProblem.INVALID_OUTPUT);
        }
        if (finish != null && finish != FinishReason.STOP) {
            return Optional.of(AnswerProblem.REFUSED);
        }
        if (response.aiMessage() == null || response.aiMessage().text() == null
                || response.aiMessage().text().isBlank()) {
            return Optional.of(AnswerProblem.INVALID_OUTPUT);
        }
        return Optional.empty();
    }

    static boolean overflow(ChatResponse response, Integer contextTokens) {
        TokenUsage usage = response == null ? null : response.tokenUsage();
        return contextTokens != null && usage != null && usage.inputTokenCount() != null
                && usage.outputTokenCount() != null
                && usage.inputTokenCount() + usage.outputTokenCount() >= contextTokens - CONTEXT_MARGIN_TOKENS;
    }

    private void backgroundRun() {
        try {
            enrichDue();
        } catch (RuntimeException e) {
            log.warn("Feed enrichment run failed ({}); the next sweep retries", SourcePoller.describe(e));
        }
    }
}
