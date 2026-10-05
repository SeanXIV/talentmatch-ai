package com.talentmatch.ai;

import com.talentmatch.ai.config.ModelInfo;
import com.talentmatch.repository.MatchJdbcRepository;
import dev.langchain4j.model.output.FinishReason;
import dev.langchain4j.model.output.TokenUsage;
import dev.langchain4j.service.Result;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.Timer;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;

/**
 * Runs one explanation generation on the AI executor: model call, finish-reason check,
 * validation, guarded persist (autocommit, outside any request transaction), circuit-breaker
 * bookkeeping, metrics and one INFO log line. Never logs prompt contents, names or keys at INFO.
 *
 * <p>Only created when AI is enabled (see {@code AiConfiguration}).
 */
public class ExplanationGenerator {

    private static final Logger log = LoggerFactory.getLogger(ExplanationGenerator.class);

    static final String TIMER = "talentmatch.ai.explanations";

    /**
     * One unit of work.
     *
     * @param prompt    the exact user message
     * @param inputHash {@link ExplanationInputHasher#hash(String)} of {@code prompt}
     */
    public record GenerationTask(JobContext job, MatchContext match, String prompt, String inputHash) {
        public GenerationTask {
            Objects.requireNonNull(job, "job");
            Objects.requireNonNull(match, "match");
            Objects.requireNonNull(prompt, "prompt");
            Objects.requireNonNull(inputHash, "inputHash");
        }
    }

    private final ExplanationAssistant assistant;
    private final MatchExplanationValidator validator;
    private final MatchJdbcRepository repository;
    private final AiCircuitBreaker circuit;
    private final ModelInfo modelInfo;
    private final Executor executor;
    private final Clock clock;
    private final MeterRegistry meterRegistry;

    public ExplanationGenerator(ExplanationAssistant assistant, MatchExplanationValidator validator,
                                MatchJdbcRepository repository, AiCircuitBreaker circuit, ModelInfo modelInfo,
                                Executor executor, Clock clock, MeterRegistry meterRegistry) {
        this.assistant = Objects.requireNonNull(assistant, "assistant");
        this.validator = Objects.requireNonNull(validator, "validator");
        this.repository = Objects.requireNonNull(repository, "repository");
        this.circuit = Objects.requireNonNull(circuit, "circuit");
        this.modelInfo = Objects.requireNonNull(modelInfo, "modelInfo");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.meterRegistry = meterRegistry == null ? Metrics.globalRegistry : meterRegistry;
    }

    public ModelInfo modelInfo() {
        return modelInfo;
    }

    /**
     * Submits the task to the AI executor. Never throws for a full executor: the returned future
     * is then already completed with {@link GenerationOutcome.Rejected}.
     */
    public CompletableFuture<GenerationOutcome> submit(GenerationTask task) {
        try {
            return CompletableFuture.supplyAsync(() -> run(task), executor);
        } catch (RejectedExecutionException e) { // includes Spring's TaskRejectedException
            log.warn("AI executor is full; explanation for candidate {} / job {} not generated",
                    task.match().candidateId(), task.job().jobId());
            recordTimer("rejected", 0L);
            return CompletableFuture.completedFuture(new GenerationOutcome.Rejected());
        }
    }

    /** Runs the task on the calling thread. Never throws. */
    GenerationOutcome run(GenerationTask task) {
        long t0 = System.nanoTime();
        GenerationOutcome outcome = null;
        TokenUsage usage = null;
        try {
            if (log.isDebugEnabled()) {
                log.debug("AI explanation request job={} candidate={} promptChars={}",
                        task.job().jobId(), task.match().candidateId(), task.prompt().length());
            }
            Result<MatchExplanation> result = assistant.explain(task.prompt());
            usage = result == null ? null : result.tokenUsage();
            outcome = evaluate(task, result, elapsedMs(t0));
        } catch (RuntimeException e) {
            FailureKind kind = FailureKind.classify(e);
            log.debug("AI explanation call failed ({})", kind, e);
            outcome = new GenerationOutcome.Failure(kind, elapsedMs(t0));
        } catch (Error e) {
            outcome = new GenerationOutcome.Failure(FailureKind.PROVIDER_ERROR, elapsedMs(t0));
            throw e;
        } finally {
            finish(task, outcome, usage);
        }
        return outcome;
    }

    private GenerationOutcome evaluate(GenerationTask task, Result<MatchExplanation> result, long latencyMs) {
        if (result == null) {
            return new GenerationOutcome.Failure(FailureKind.INVALID_OUTPUT, latencyMs);
        }
        FinishReason finish = result.finishReason();
        if (finish == FinishReason.LENGTH) {
            return new GenerationOutcome.Failure(FailureKind.INVALID_OUTPUT, latencyMs);
        }
        if (finish != null && finish != FinishReason.STOP) {
            return new GenerationOutcome.Failure(FailureKind.REFUSED, latencyMs);
        }
        Optional<MatchExplanation> valid = validator.normalize(result.content(), task.match().evaluation());
        if (valid.isEmpty()) {
            return new GenerationOutcome.Failure(FailureKind.INVALID_OUTPUT, latencyMs);
        }
        MatchExplanation value = valid.get();
        // PostgreSQL timestamptz keeps microseconds; truncate so this response and later reads agree
        Instant generatedAt = clock.instant().truncatedTo(ChronoUnit.MICROS);
        boolean persisted = persist(task, value, generatedAt);
        return new GenerationOutcome.Success(value, modelInfo.label(), generatedAt, latencyMs, persisted);
    }

    private boolean persist(GenerationTask task, MatchExplanation value, Instant generatedAt) {
        MatchContext m = task.match();
        try {
            int rows = repository.saveExplanation(task.job().jobId(), m.candidateId(), m.storedScore(),
                    m.candidateUpdatedAt(), task.job().updatedAt(), value.explanation(),
                    ExplanationPayloadCodec.toJson(value), task.inputHash(), modelInfo.label(), generatedAt);
            if (rows == 0) {
                log.info("AI explanation for candidate {} / job {} not stored: the candidate, job or score "
                        + "changed while it was generated", m.candidateId(), task.job().jobId());
            }
            return rows > 0;
        } catch (DataAccessException e) {
            log.warn("Could not store AI explanation for candidate {} / job {}: {}",
                    m.candidateId(), task.job().jobId(), e.getClass().getSimpleName());
            return false;
        }
    }

    private void finish(GenerationTask task, GenerationOutcome outcome, TokenUsage usage) {
        String outcomeTag;
        long latencyMs;
        if (outcome instanceof GenerationOutcome.Success s) {
            circuit.recordSuccess();
            outcomeTag = "success";
            latencyMs = s.latencyMs();
        } else if (outcome instanceof GenerationOutcome.Failure f) {
            if (f.kind().providerFault()) {
                circuit.recordFailure(f.kind());
            } else {
                circuit.recordSuccess();
            }
            outcomeTag = f.kind().tag();
            latencyMs = f.latencyMs();
        } else {
            outcomeTag = "rejected";
            latencyMs = 0L;
        }
        try {
            recordTimer(outcomeTag, latencyMs);
        } catch (RuntimeException e) {
            log.debug("Could not record AI metrics", e);
        }
        log.info("AI explanation job={} candidate={} provider={} model={} outcome={} latencyMs={} "
                        + "inputTokens={} outputTokens={}",
                task.job().jobId(), task.match().candidateId(), modelInfo.provider().id(), modelInfo.modelName(),
                outcomeTag, latencyMs,
                usage == null ? null : usage.inputTokenCount(),
                usage == null ? null : usage.outputTokenCount());
    }

    private void recordTimer(String outcomeTag, long latencyMs) {
        Timer.builder(TIMER)
                .description("AI explanation generations")
                .tag("provider", modelInfo.provider().id())
                .tag("model", modelInfo.modelName())
                .tag("outcome", outcomeTag)
                .register(meterRegistry)
                .record(latencyMs, TimeUnit.MILLISECONDS);
    }

    private static long elapsedMs(long t0) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
    }
}
