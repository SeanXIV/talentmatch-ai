package com.talentmatch.ai;

import static com.talentmatch.ai.AiFixtures.job;
import static com.talentmatch.ai.AiFixtures.match;
import static com.talentmatch.ai.AiFixtures.props;
import static com.talentmatch.ai.AiFixtures.validExplanation;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.core.JsonParseException;
import com.talentmatch.ai.config.ModelInfo;
import com.talentmatch.repository.MatchJdbcRepository;
import com.talentmatch.support.MutableClock;
import dev.langchain4j.model.output.FinishReason;
import dev.langchain4j.model.output.TokenUsage;
import dev.langchain4j.service.Result;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.dao.DataAccessResourceFailureException;

/** Spec §2 ExplanationGenerator task, FailureKind classification, circuit bookkeeping, metrics, logging. */
@ExtendWith(OutputCaptureExtension.class)
class ExplanationGeneratorTest {

    private final MutableClock clock = MutableClock.fixedAt(AiFixtures.T0);
    private final ExplanationAssistant assistant = mock(ExplanationAssistant.class);
    private final MatchJdbcRepository repo = mock(MatchJdbcRepository.class);
    private final AiCircuitBreaker circuit = new AiCircuitBreaker(props(), clock);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final ModelInfo model = new ModelInfo(AiProvider.OLLAMA, "qwen2.5:7b-instruct");
    private final JobContext job = job("Secret job description text");
    private final MatchContext row = match(1, "Zelda Quixote", "Secret candidate summary text", AiFixtures.ada(), null);
    private final ExplanationGenerator.GenerationTask task = new ExplanationGenerator.GenerationTask(job, row,
            "PROMPT " + row.candidateSummary(), "a".repeat(64));

    private ExplanationGenerator generator(Executor executor) {
        return new ExplanationGenerator(assistant, new MatchExplanationValidator(), repo, circuit, model, executor,
                clock, meters);
    }

    private ExplanationGenerator generator() {
        return generator(Runnable::run);
    }

    private static Result<MatchExplanation> result(MatchExplanation content, FinishReason finish) {
        return Result.<MatchExplanation>builder().content(content).finishReason(finish)
                .tokenUsage(new TokenUsage(123, 45)).build();
    }

    private void persistReturns(int rows) {
        when(repo.saveExplanation(any(), any(), anyDouble(), any(), any(), anyString(), anyString(), anyString(),
                anyString(), any())).thenReturn(rows);
    }

    private double timerCount(String outcome) {
        var t = meters.find("talentmatch.ai.explanations").tag("outcome", outcome).timer();
        return t == null ? 0 : t.count();
    }

    @Test
    void successValidatesPersistsWithGuardAndClosesTheCircuit() throws Exception {
        persistReturns(1);
        circuit.recordFailure(FailureKind.TIMEOUT);
        when(assistant.explain(task.prompt())).thenReturn(result(validExplanation(), FinishReason.STOP));

        GenerationOutcome o = generator().submit(task).get(5, TimeUnit.SECONDS);

        assertThat(o).isInstanceOf(GenerationOutcome.Success.class);
        GenerationOutcome.Success s = (GenerationOutcome.Success) o;
        assertThat(s.persisted()).isTrue();
        assertThat(s.model()).isEqualTo("ollama/qwen2.5:7b-instruct");
        assertThat(s.generatedAt()).isEqualTo(AiFixtures.T0);
        assertThat(s.value()).isEqualTo(validExplanation());
        verify(repo).saveExplanation(eq(job.jobId()), eq(row.candidateId()), eq(row.storedScore()),
                eq(row.candidateUpdatedAt()), eq(job.updatedAt()), eq(validExplanation().explanation()),
                org.mockito.ArgumentMatchers.argThat(json -> json.contains("\"headline\":\"Strong fit with every required skill\"")
                        && json.contains("\"strengths\":[") && json.contains("\"gaps\":[")),
                eq("a".repeat(64)), eq("ollama/qwen2.5:7b-instruct"), eq(AiFixtures.T0));
        assertThat(circuit.consecutiveFailures()).isZero();
        assertThat(circuit.lastSuccessAt()).isEqualTo(AiFixtures.T0);
        assertThat(timerCount("success")).isEqualTo(1);
    }

    @Test
    void nullFinishReasonIsAccepted() {
        persistReturns(1);
        org.mockito.Mockito.doReturn(result(validExplanation(), null)).when(assistant).explain(any());
        assertThat(generator().run(task)).isInstanceOf(GenerationOutcome.Success.class);
    }

    @Test
    void guardMissAndDbErrorStillSucceedButAreNotPersisted() {
        org.mockito.Mockito.doReturn(result(validExplanation(), FinishReason.STOP)).when(assistant).explain(any());
        persistReturns(0);
        assertThat(((GenerationOutcome.Success) generator().run(task)).persisted()).isFalse();

        org.mockito.Mockito.doThrow(new DataAccessResourceFailureException("db down")).when(repo).saveExplanation(
                any(), any(), anyDouble(), any(), any(), anyString(), anyString(), anyString(), anyString(), any());
        GenerationOutcome o = generator().run(task);
        assertThat(o).isInstanceOf(GenerationOutcome.Success.class);
        assertThat(((GenerationOutcome.Success) o).persisted()).isFalse();
    }

    @Test
    void finishReasonsMapToRefusedOrInvalidAndNothingIsPersisted() {
        for (FinishReason f : List.of(FinishReason.CONTENT_FILTER, FinishReason.OTHER, FinishReason.TOOL_EXECUTION)) {
            org.mockito.Mockito.doReturn(result(validExplanation(), f)).when(assistant).explain(any());
            assertThat(((GenerationOutcome.Failure) generator().run(task)).kind()).as(f.name())
                    .isEqualTo(FailureKind.REFUSED);
        }
        org.mockito.Mockito.doReturn(result(validExplanation(), FinishReason.LENGTH)).when(assistant).explain(any());
        assertThat(((GenerationOutcome.Failure) generator().run(task)).kind()).isEqualTo(FailureKind.INVALID_OUTPUT);
        verifyNoInteractions(repo);
        // the provider answered: refusals and invalid output never count against the circuit
        assertThat(circuit.consecutiveFailures()).isZero();
        assertThat(timerCount("refused")).isPositive();
        assertThat(timerCount("invalid_output")).isPositive();
    }

    @Test
    void invalidContentOrNullResultIsInvalidOutput() {
        when(assistant.explain(any())).thenReturn(result(new MatchExplanation(" ", "short", null, null),
                FinishReason.STOP));
        assertThat(((GenerationOutcome.Failure) generator().run(task)).kind()).isEqualTo(FailureKind.INVALID_OUTPUT);
        org.mockito.Mockito.doReturn(result(null, FinishReason.STOP)).when(assistant).explain(any());
        assertThat(((GenerationOutcome.Failure) generator().run(task)).kind()).isEqualTo(FailureKind.INVALID_OUTPUT);
        org.mockito.Mockito.doReturn(null).when(assistant).explain(any());
        assertThat(((GenerationOutcome.Failure) generator().run(task)).kind()).isEqualTo(FailureKind.INVALID_OUTPUT);
        verify(repo, never()).saveExplanation(any(), any(), anyDouble(), any(), any(), any(), any(), any(), any(),
                any());
    }

    @Test
    void exceptionsAreClassifiedAndProviderFaultsFeedTheCircuit() {
        org.mockito.Mockito.doThrow(new IllegalStateException("connection refused")).when(assistant).explain(any());
        assertThat(((GenerationOutcome.Failure) generator().run(task)).kind()).isEqualTo(FailureKind.PROVIDER_ERROR);
        assertThat(circuit.consecutiveFailures()).isEqualTo(1);

        org.mockito.Mockito.doThrow(new RuntimeException(new HttpTimeoutException("slow"))).when(assistant).explain(any());
        assertThat(((GenerationOutcome.Failure) generator().run(task)).kind()).isEqualTo(FailureKind.TIMEOUT);
        assertThat(circuit.consecutiveFailures()).isEqualTo(2);
        assertThat(circuit.lastFailureKind()).isEqualTo(FailureKind.TIMEOUT);

        org.mockito.Mockito.doThrow(new RuntimeException(new JsonParseException(null, "bad json"))).when(assistant).explain(any());
        assertThat(((GenerationOutcome.Failure) generator().run(task)).kind()).isEqualTo(FailureKind.INVALID_OUTPUT);
        assertThat(circuit.consecutiveFailures()).as("parsing failure = provider reachable").isZero();
        assertThat(timerCount("provider_error")).isEqualTo(1);
        assertThat(timerCount("timeout")).isEqualTo(1);
    }

    @Test
    void failureKindClassification() {
        assertThat(FailureKind.classify(new TimeoutException())).isEqualTo(FailureKind.TIMEOUT);
        assertThat(FailureKind.classify(new RuntimeException(new RuntimeException(new SocketTimeoutException()))))
                .isEqualTo(FailureKind.TIMEOUT);
        assertThat(FailureKind.classify(new ModelTimeoutThing())).isEqualTo(FailureKind.TIMEOUT);
        assertThat(FailureKind.classify(new RuntimeException(new OutputParsingThing()))).isEqualTo(FailureKind.INVALID_OUTPUT);
        assertThat(FailureKind.classify(new RuntimeException(new JsonParseException(null, "x"))))
                .isEqualTo(FailureKind.INVALID_OUTPUT);
        // timeout wins over parsing anywhere in the chain
        assertThat(FailureKind.classify(new OutputParsingThing(new TimeoutException()))).isEqualTo(FailureKind.TIMEOUT);
        assertThat(FailureKind.classify(new IllegalStateException("x"))).isEqualTo(FailureKind.PROVIDER_ERROR);
        assertThat(FailureKind.classify(null)).isEqualTo(FailureKind.PROVIDER_ERROR);
        RuntimeException loop = new RuntimeException("a");
        RuntimeException other = new RuntimeException("b", loop);
        loop.initCause(other);
        assertThat(FailureKind.classify(loop)).as("cause cycles terminate").isEqualTo(FailureKind.PROVIDER_ERROR);
        assertThat(FailureKind.TIMEOUT.providerFault()).isTrue();
        assertThat(FailureKind.PROVIDER_ERROR.providerFault()).isTrue();
        assertThat(FailureKind.REFUSED.providerFault()).isFalse();
        assertThat(FailureKind.INVALID_OUTPUT.providerFault()).isFalse();
        assertThat(FailureKind.PROVIDER_ERROR.tag()).isEqualTo("provider_error");
    }

    @Test
    void fullExecutorReturnsACompletedRejectedFutureWithoutCallingTheModel() {
        for (RuntimeException rejection : List.of(new java.util.concurrent.RejectedExecutionException("full"),
                new TaskRejectedException("full"))) {
            var f = generator(r -> { throw rejection; }).submit(task);
            assertThat(f).isDone();
            assertThat(f.join()).isInstanceOf(GenerationOutcome.Rejected.class);
        }
        verifyNoInteractions(assistant);
        assertThat(timerCount("rejected")).isEqualTo(2);
        assertThat(circuit.consecutiveFailures()).isZero();
    }

    @Test
    void infoLogLineHasOutcomeButNoPromptNamesOrSummaries(CapturedOutput out) {
        persistReturns(1);
        org.mockito.Mockito.doReturn(result(validExplanation(), FinishReason.STOP)).when(assistant).explain(any());
        generator().run(task);
        String line = out.getAll().lines().filter(l -> l.contains("AI explanation job=")).findFirst().orElseThrow();
        assertThat(line).contains("INFO")
                .contains("job=" + job.jobId())
                .contains("candidate=" + row.candidateId())
                .contains("provider=ollama")
                .contains("model=qwen2.5:7b-instruct")
                .contains("outcome=success")
                .contains("latencyMs=")
                .contains("inputTokens=123")
                .contains("outputTokens=45");
        assertThat(out.getAll()).doesNotContain("Zelda").doesNotContain("Secret candidate summary")
                .doesNotContain("Secret job description").doesNotContain("PROMPT ");
    }

    @Test
    void generationTaskRequiresAllFields() {
        org.assertj.core.api.Assertions.assertThatNullPointerException()
                .isThrownBy(() -> new ExplanationGenerator.GenerationTask(job, row, null, "h"));
        assertThat(Instant.EPOCH).isNotNull();
    }

    static class ModelTimeoutThing extends RuntimeException {
    }

    static class OutputParsingThing extends RuntimeException {
        OutputParsingThing() {
        }

        OutputParsingThing(Throwable cause) {
            super(cause);
        }
    }
}
