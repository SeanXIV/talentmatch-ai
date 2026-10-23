package com.talentmatch.application.tailoring;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.talentmatch.ai.FailureKind;
import com.talentmatch.ai.Interrupts;
import com.talentmatch.application.content.CoverLetterContent;
import com.talentmatch.application.content.DocumentKind;
import com.talentmatch.application.content.TailoredCvContent;
import com.talentmatch.application.tailoring.TailoringPromptBuilder.TailoringPrompt;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ResponseFormat;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.FinishReason;
import dev.langchain4j.model.output.TokenUsage;
import java.time.LocalDate;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * One tailoring call per document kind (§4.6): prompt → model (hand-built JSON schema) → response check →
 * parse → assemble (grounding) → {@link TailoringOutcome}. No database access, no transactions, no gate:
 * the caller ({@code GenerationService}) holds {@code LocalModelGate} around the calls, measures latency,
 * records metrics and logs the per-kind line.
 *
 * <p>Response checks follow {@code ResumeExtractionService.check}: context overflow first (within
 * {@value #CONTEXT_MARGIN_TOKENS} tokens of {@code num_ctx}), then {@code LENGTH} → INVALID_OUTPUT, any
 * other non-STOP finish → REFUSED, then a missing or blank answer → INVALID_OUTPUT. Model exceptions are
 * classified by {@link FailureKind#classify}. An assembler rejection is INVALID_OUTPUT.
 *
 * <p><b>Interrupts</b> (shutdown) are rethrown unchanged, so the caller can leave the generation RUNNING
 * for recovery. Every other failure is returned as {@link TailoringOutcome.Failed}; this never throws for a
 * bad answer.
 *
 * <p>Never logs prompts, answers or exception messages (parse errors quote the answer): exception class
 * names only.
 */
@Component
public class TailoringEngine {

    private static final Logger log = LoggerFactory.getLogger(TailoringEngine.class);

    /** Prompt + output within this many tokens of num_ctx means Ollama probably cut the prompt. */
    static final int CONTEXT_MARGIN_TOKENS = 32;
    private static final int MAX_CAUSE_DEPTH = 16;

    private final ApplicationProperties properties;
    private final TailoringPromptBuilder prompts;

    public TailoringEngine(ApplicationProperties properties) {
        this.properties = Objects.requireNonNull(properties, "properties");
        this.prompts = new TailoringPromptBuilder(properties);
    }

    /**
     * Per-generation options for the cover letter (ignored for the CV).
     *
     * @param date                the letter date (today in {@code talentmatch.applications.zone})
     * @param mentionNoticePeriod append the fixed notice-period sentence (decision p)
     * @param noticePeriodDays    {@code job_preferences.noticePeriodDays}; null when unknown or not read
     */
    public record Options(LocalDate date, boolean mentionNoticePeriod, Integer noticePeriodDays) {

        public Options {
            Objects.requireNonNull(date, "date");
        }
    }

    /** The prompt builder (for the caller's input hash or tests). */
    public TailoringPromptBuilder prompts() {
        return prompts;
    }

    /**
     * Generates one kind.
     *
     * @param model   the tailoring model (availability and the remote opt-in are the caller's checks)
     * @param job     the job read at generation time
     * @param facts   the profile version, prepared with the same {@code check}
     * @param check   the fact check (vocabulary loaded once per generation)
     * @param options letter options; may be null for the CV
     * @return {@code TailoringOutcome<TailoredCvContent>} for CV, {@code TailoringOutcome<CoverLetterContent>}
     *         for COVER_LETTER
     */
    public TailoringOutcome<?> generate(DocumentKind kind, TailoringModel model, JobFacts job, ProfileFacts facts,
                                        FactCheck check, Options options) {
        return switch (Objects.requireNonNull(kind, "kind")) {
            case CV -> generateCv(model, job, facts, check);
            case COVER_LETTER -> generateCoverLetter(model, job, facts, check,
                    Objects.requireNonNull(options, "options"));
        };
    }

    public TailoringOutcome<TailoredCvContent> generateCv(TailoringModel model, JobFacts job, ProfileFacts facts,
                                                          FactCheck check) {
        TailoringPrompt prompt = prompts.buildCv(job, facts);
        return call(model, prompt, TailoredCvJsonSchema.responseFormat(), TailoredCvJsonSchema::parse,
                (TailoredCvDraft draft) -> TailoredCvAssembler.assemble(draft, facts, job, check));
    }

    public TailoringOutcome<CoverLetterContent> generateCoverLetter(TailoringModel model, JobFacts job,
                                                                    ProfileFacts facts, FactCheck check,
                                                                    Options options) {
        TailoringPrompt prompt = prompts.buildCoverLetter(job, facts);
        CoverLetterAssembler.Options letter = new CoverLetterAssembler.Options(options.date(),
                options.mentionNoticePeriod(), options.noticePeriodDays(),
                properties.coverLetter().minWords(), properties.coverLetter().maxWords());
        return call(model, prompt, CoverLetterJsonSchema.responseFormat(), CoverLetterJsonSchema::parse,
                (CoverLetterDraft draft) -> CoverLetterAssembler.assemble(draft, facts, job, check, letter));
    }

    /** The request sent for a prompt: system + user message and the structured-output schema. */
    static ChatRequest request(TailoringPrompt prompt, ResponseFormat format) {
        return ChatRequest.builder()
                .messages(SystemMessage.from(prompt.system()), UserMessage.from(prompt.user()))
                .responseFormat(format)
                .build();
    }

    /** Parses a model answer; throws for malformed JSON. */
    @FunctionalInterface
    interface Parser<D> {
        D parse(String text) throws JsonProcessingException;
    }

    private <D, T> TailoringOutcome<T> call(TailoringModel model, TailoringPrompt prompt, ResponseFormat format,
                                            Parser<D> parser, Function<D, AssemblyResult<T>> assembler) {
        Objects.requireNonNull(model, "model");
        ChatResponse response;
        try {
            response = model.chatModel().chat(request(prompt, format));
        } catch (RuntimeException e) {
            if (Interrupts.interrupted(e)) {
                throw e;                                   // shutdown: the caller leaves the row RUNNING
            }
            FailureKind kind = FailureKind.classify(e);
            log.debug("Tailoring {} call failed ({}: {})", prompt.kind(), kind, describe(e));
            return new TailoringOutcome.Failed<>(GenerationFailure.of(kind), null, null);
        }
        TokenUsage usage = response == null ? null : response.tokenUsage();
        Optional<GenerationFailure> problem = check(response, model.contextTokens());
        if (problem.isPresent()) {
            return new TailoringOutcome.Failed<>(problem.get(), null, usage);
        }
        D draft;
        try {
            draft = parser.parse(response.aiMessage().text());
        } catch (JsonProcessingException e) {
            log.debug("Tailoring {} answer was not valid JSON ({})", prompt.kind(), e.getClass().getSimpleName());
            return new TailoringOutcome.Failed<>(GenerationFailure.INVALID_OUTPUT, "unparseable answer", usage);
        }
        AssemblyResult<T> result;
        try {
            result = assembler.apply(draft);
        } catch (RuntimeException e) {
            // An answer shape the assembler didn't expect must not cost the other kind: treat it as unusable.
            log.error("Tailoring {} answer could not be assembled ({}); treating it as invalid output",
                    prompt.kind(), describe(e));
            return new TailoringOutcome.Failed<>(GenerationFailure.INVALID_OUTPUT, "assembly error", usage);
        }
        if (result instanceof AssemblyResult.Assembled<T> assembled) {
            return new TailoringOutcome.Generated<>(assembled.content(), assembled.findings(), prompt.inputHash(),
                    usage);
        }
        String reason = result instanceof AssemblyResult.Rejected<T> rejected ? rejected.reason() : null;
        return new TailoringOutcome.Failed<>(GenerationFailure.INVALID_OUTPUT, reason, usage);
    }

    /**
     * Context overflow first (a cut prompt can still end with STOP or LENGTH), then the finish reason
     * (LENGTH means the JSON was cut off; anything else but STOP is a refusal, which often comes with no
     * text at all), and only then a missing or blank answer.
     */
    static Optional<GenerationFailure> check(ChatResponse response, Integer contextTokens) {
        if (response == null) {
            return Optional.of(GenerationFailure.INVALID_OUTPUT);
        }
        TokenUsage usage = response.tokenUsage();
        if (contextTokens != null && usage != null && usage.inputTokenCount() != null
                && usage.outputTokenCount() != null
                && usage.inputTokenCount() + usage.outputTokenCount() >= contextTokens - CONTEXT_MARGIN_TOKENS) {
            return Optional.of(GenerationFailure.CONTEXT_OVERFLOW);
        }
        FinishReason finish = response.finishReason();
        if (finish == FinishReason.LENGTH) {
            return Optional.of(GenerationFailure.INVALID_OUTPUT);
        }
        if (finish != null && finish != FinishReason.STOP) {
            return Optional.of(GenerationFailure.REFUSED);
        }
        if (response.aiMessage() == null || response.aiMessage().text() == null
                || response.aiMessage().text().isBlank()) {
            return Optional.of(GenerationFailure.INVALID_OUTPUT);
        }
        return Optional.empty();
    }

    /** Exception class names along the cause chain. Never messages. */
    static String describe(Throwable failure) {
        StringBuilder sb = new StringBuilder();
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        int depth = 0;
        for (Throwable t = failure; t != null && depth < MAX_CAUSE_DEPTH && seen.add(t); t = t.getCause(), depth++) {
            if (!sb.isEmpty()) {
                sb.append(" <- ");
            }
            sb.append(t.getClass().getSimpleName());
        }
        return sb.toString();
    }
}
