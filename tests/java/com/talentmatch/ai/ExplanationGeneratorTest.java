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
import com.talentmatch.repository.ExplanationTarget;
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
        when(repo.saveExplanation(eq(ExplanationTarget.RECRUITER), any(), any(), anyDouble(), any(), any(),
                anyString(), anyString(), anyString(), anyString(), any())).thenReturn(rows);
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
        verify(repo).saveExplanation(eq(ExplanationTarget.RECRUITER), eq(job.jobId()), eq(row.candidateId()), eq(row.storedScore()),
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
                any(ExplanationTarget.class), any(), any(), anyDouble(), any(), any(), anyString(), anyString(),
                anyString(), anyString(), any());
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
        verifyNoInteractions(repo);
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

    // ------------------------------------------------------------------ Phase 6 step 2: audience

    private ExplanationGenerator.GenerationTask seekerTask() {
        return new ExplanationGenerator.GenerationTask(job, row, task.prompt(), task.inputHash(), Audience.SEEKER);
    }

    @Test
    void fourArgTaskDefaultsToRecruiterAndAudienceIsRequired() {
        assertThat(task.audience()).isEqualTo(Audience.RECRUITER);
        assertThat(new ExplanationGenerator.GenerationTask(job, row, "p", "h", Audience.RECRUITER))
                .isEqualTo(new ExplanationGenerator.GenerationTask(job, row, "p", "h"));
        org.assertj.core.api.Assertions.assertThatNullPointerException()
                .isThrownBy(() -> new ExplanationGenerator.GenerationTask(job, row, "p", "h", null));
    }

    @Test
    void recruiterPersistsOnlyThroughTheRecruiterTarget() {
        persistReturns(1);
        org.mockito.Mockito.doReturn(result(validExplanation(), FinishReason.STOP)).when(assistant).explain(any());
        assertThat(((GenerationOutcome.Success) generator().run(task)).persisted()).isTrue();
        verify(repo).saveExplanation(eq(ExplanationTarget.RECRUITER), any(), any(), anyDouble(), any(), any(),
                any(), any(), any(), any(), any());
        verify(repo, never()).saveExplanation(eq(ExplanationTarget.SEEKER), any(), any(), anyDouble(), any(), any(),
                any(), any(), any(), any(), any());
        // the legacy 10-arg overload is no longer used by the generator
        verify(repo, never()).saveExplanation(any(java.util.UUID.class), any(), anyDouble(), any(), any(), any(),
                any(), any(), any(), any());
    }

    // ------------------------------------------------------------------ Phase 6 step 3: SEEKER

    private final SeekerExplanationAssistant seekerAssistant = mock(SeekerExplanationAssistant.class);
    private final ExplanationGroundingCheck groundingCheck = mock(ExplanationGroundingCheck.class);

    private ExplanationGenerator seekerGenerator(ExplanationGroundingCheck check, LocalModelGate gate) {
        return new ExplanationGenerator(assistant, seekerAssistant, check, new MatchExplanationValidator(), repo,
                circuit, model, Runnable::run, clock, meters, gate);
    }

    private ExplanationGenerator seekerGenerator() {
        return seekerGenerator(groundingCheck, null);
    }

    /** Grounded for the ada() breakdown: second person, matched and missing skills only. */
    private static MatchExplanation seekerExplanation() {
        return new MatchExplanation("A strong fit for your Java and SQL",
                "You have both required skills, Java with 5 years and SQL. You're missing Kubernetes, a nice-to-have.",
                List.of("Java (5 years)", "SQL"), List.of("Kubernetes (nice-to-have)"));
    }

    private void seekerPersistReturns(int rows) {
        when(repo.saveExplanation(eq(ExplanationTarget.SEEKER), any(), any(), anyDouble(), any(), any(),
                anyString(), anyString(), anyString(), anyString(), any())).thenReturn(rows);
    }

    private double seekerTimer(String outcome) {
        var t = meters.find("talentmatch.ai.explanations").tag("audience", "seeker").tag("outcome", outcome).timer();
        return t == null ? 0 : t.count();
    }

    @Test
    void seekerUsesTheSeekerAssistantAndPersistsToTheSeekerColumns() throws Exception {
        seekerPersistReturns(1);
        when(groundingCheck.accept(any(), any(), any(), any())).thenReturn(true);
        when(seekerAssistant.explain(task.prompt())).thenReturn(result(seekerExplanation(), FinishReason.STOP));

        GenerationOutcome o = seekerGenerator().submit(seekerTask()).get(5, TimeUnit.SECONDS);

        assertThat(o).isInstanceOf(GenerationOutcome.Success.class);
        GenerationOutcome.Success s = (GenerationOutcome.Success) o;
        assertThat(s.persisted()).isTrue();
        assertThat(s.value()).isEqualTo(seekerExplanation());
        verifyNoInteractions(assistant);
        verify(groundingCheck).accept(seekerExplanation(), row.evaluation(), job.title(), job.company());
        verify(repo).saveExplanation(eq(ExplanationTarget.SEEKER), eq(job.jobId()), eq(row.candidateId()),
                eq(row.storedScore()), eq(row.candidateUpdatedAt()), eq(job.updatedAt()),
                eq(seekerExplanation().explanation()),
                org.mockito.ArgumentMatchers.argThat(json -> json.contains("\"headline\":\"A strong fit for your Java and SQL\"")),
                eq("a".repeat(64)), eq("ollama/qwen2.5:7b-instruct"), eq(AiFixtures.T0));
        verify(repo, never()).saveExplanation(eq(ExplanationTarget.RECRUITER), any(), any(), anyDouble(), any(),
                any(), any(), any(), any(), any(), any());
        assertThat(seekerTimer("success")).isEqualTo(1);
        assertThat(circuit.lastSuccessAt()).isEqualTo(AiFixtures.T0);
    }

    @Test
    void recruiterNeverUsesTheSeekerAssistantOrTheGroundingCheck() {
        persistReturns(1);
        org.mockito.Mockito.doReturn(result(validExplanation(), FinishReason.STOP)).when(assistant).explain(any());
        GenerationOutcome o = seekerGenerator().run(task);
        assertThat(((GenerationOutcome.Success) o).persisted()).isTrue();
        verifyNoInteractions(seekerAssistant, groundingCheck);
        verify(repo).saveExplanation(eq(ExplanationTarget.RECRUITER), any(), any(), anyDouble(), any(), any(),
                any(), any(), any(), any(), any());
    }

    @Test
    void groundingRejectionIsInvalidOutputWithACircuitSuccessAndNothingStored(CapturedOutput out) {
        circuit.recordFailure(FailureKind.TIMEOUT);
        when(groundingCheck.accept(any(), any(), any(), any())).thenReturn(false);
        when(seekerAssistant.explain(any())).thenReturn(result(seekerExplanation(), FinishReason.STOP));

        GenerationOutcome o = seekerGenerator().run(seekerTask());

        assertThat(o).isInstanceOf(GenerationOutcome.Failure.class);
        assertThat(((GenerationOutcome.Failure) o).kind()).isEqualTo(FailureKind.INVALID_OUTPUT);
        verifyNoInteractions(repo);
        assertThat(circuit.consecutiveFailures()).as("not a provider fault").isZero();
        assertThat(circuit.lastSuccessAt()).isEqualTo(AiFixtures.T0);
        assertThat(seekerTimer("invalid_output")).isEqualTo(1);
        assertThat(out.getAll()).contains("AI seeker explanation for candidate " + row.candidateId())
                .contains("rejected by the grounding check")
                .contains("audience=seeker").contains("outcome=invalid_output")
                .doesNotContain(seekerExplanation().explanation()).doesNotContain(seekerExplanation().headline())
                .doesNotContain("Zelda").doesNotContain("Secret").doesNotContain("PROMPT ");
    }

    @Test
    void groundingCheckThatThrowsRejects(CapturedOutput out) {
        when(groundingCheck.accept(any(), any(), any(), any())).thenThrow(new IllegalStateException("boom secret"));
        when(seekerAssistant.explain(any())).thenReturn(result(seekerExplanation(), FinishReason.STOP));
        GenerationOutcome o = seekerGenerator().run(seekerTask());
        assertThat(((GenerationOutcome.Failure) o).kind()).isEqualTo(FailureKind.INVALID_OUTPUT);
        verifyNoInteractions(repo);
        assertThat(circuit.consecutiveFailures()).isZero();
        assertThat(out.getAll()).contains("the grounding check failed (IllegalStateException)")
                .doesNotContain("boom secret").doesNotContain(seekerExplanation().explanation());
    }

    @Test
    void noGroundingCheckRejectsEverySeekerOutput(CapturedOutput out) {
        when(seekerAssistant.explain(any())).thenReturn(result(seekerExplanation(), FinishReason.STOP));
        GenerationOutcome o = seekerGenerator(null, null).run(seekerTask());
        assertThat(((GenerationOutcome.Failure) o).kind()).isEqualTo(FailureKind.INVALID_OUTPUT);
        verifyNoInteractions(repo);
        verify(seekerAssistant).explain(task.prompt());
        assertThat(circuit.lastSuccessAt()).as("the provider answered").isEqualTo(AiFixtures.T0);
        assertThat(out.getAll()).contains("no grounding check is configured");

        // recruiter output does not need a check
        persistReturns(1);
        org.mockito.Mockito.doReturn(result(validExplanation(), FinishReason.STOP)).when(assistant).explain(any());
        assertThat(seekerGenerator(null, null).run(task)).isInstanceOf(GenerationOutcome.Success.class);
    }

    @Test
    void validatorRunsBeforeTheGroundingCheck() {
        when(seekerAssistant.explain(any())).thenReturn(result(new MatchExplanation("H",
                "You have Java and SQL. See https://evil.example for more.", null, null), FinishReason.STOP));
        assertThat(((GenerationOutcome.Failure) seekerGenerator().run(seekerTask())).kind())
                .isEqualTo(FailureKind.INVALID_OUTPUT);
        verifyNoInteractions(groundingCheck, repo);

        // the check sees the normalized value
        when(groundingCheck.accept(any(), any(), any(), any())).thenReturn(true);
        seekerPersistReturns(1);
        when(seekerAssistant.explain(any())).thenReturn(result(new MatchExplanation("  A strong\n fit ",
                "You have Java and SQL,   the required skills.", List.of("- Java", "Python"), null), FinishReason.STOP));
        assertThat(seekerGenerator().run(seekerTask())).isInstanceOf(GenerationOutcome.Success.class);
        verify(groundingCheck).accept(new MatchExplanation("A strong fit", "You have Java and SQL, the required skills.",
                List.of("Java"), List.of()), row.evaluation(), job.title(), job.company());
    }

    @Test
    void seekerFinishReasonAndProviderFailuresBehaveLikeRecruiter() {
        org.mockito.Mockito.doReturn(result(seekerExplanation(), FinishReason.CONTENT_FILTER)).when(seekerAssistant)
                .explain(any());
        assertThat(((GenerationOutcome.Failure) seekerGenerator().run(seekerTask())).kind())
                .isEqualTo(FailureKind.REFUSED);
        org.mockito.Mockito.doReturn(result(seekerExplanation(), FinishReason.LENGTH)).when(seekerAssistant)
                .explain(any());
        assertThat(((GenerationOutcome.Failure) seekerGenerator().run(seekerTask())).kind())
                .isEqualTo(FailureKind.INVALID_OUTPUT);
        verifyNoInteractions(groundingCheck, repo);
        assertThat(circuit.consecutiveFailures()).isZero();

        org.mockito.Mockito.doThrow(new RuntimeException(new HttpTimeoutException("slow"))).when(seekerAssistant)
                .explain(any());
        assertThat(((GenerationOutcome.Failure) seekerGenerator().run(seekerTask())).kind())
                .isEqualTo(FailureKind.TIMEOUT);
        assertThat(circuit.consecutiveFailures()).as("seeker provider faults feed the shared circuit").isEqualTo(1);
        assertThat(seekerTimer("timeout")).isEqualTo(1);
        assertThat(seekerTimer("refused")).isEqualTo(1);
    }

    @Test
    void seekerTasksShareTheLocalModelGate() {
        LocalModelGate gate = mock(LocalModelGate.class);
        when(gate.tryAcquireShared()).thenReturn(false);
        assertThat(seekerGenerator(groundingCheck, gate).run(seekerTask())).isInstanceOf(GenerationOutcome.Rejected.class);
        verifyNoInteractions(seekerAssistant, groundingCheck, repo);
        assertThat(seekerTimer("rejected")).isEqualTo(1);

        LocalModelGate open = mock(LocalModelGate.class);
        when(open.tryAcquireShared()).thenReturn(true);
        when(groundingCheck.accept(any(), any(), any(), any())).thenReturn(true);
        seekerPersistReturns(1);
        when(seekerAssistant.explain(any())).thenReturn(result(seekerExplanation(), FinishReason.STOP));
        assertThat(seekerGenerator(groundingCheck, open).run(seekerTask())).isInstanceOf(GenerationOutcome.Success.class);
        verify(open).releaseShared();
    }

    @Test
    void seekerWithoutASeekerAssistantFailsClosedWithoutModelGateCircuitOrExecutor() {
        LocalModelGate gate = mock(LocalModelGate.class);
        java.util.concurrent.atomic.AtomicInteger executed = new java.util.concurrent.atomic.AtomicInteger();
        Executor counting = r -> {
            executed.incrementAndGet();
            r.run();
        };
        // the Phase 3 (9-arg) constructor and the 11-arg one with a null seeker assistant
        List<ExplanationGenerator> generators = List.of(
                new ExplanationGenerator(assistant, new MatchExplanationValidator(), repo, circuit, model, counting,
                        clock, meters, gate),
                new ExplanationGenerator(assistant, null, groundingCheck, new MatchExplanationValidator(), repo,
                        circuit, model, counting, clock, meters, gate));
        for (ExplanationGenerator g : generators) {
            var f = g.submit(seekerTask());
            assertThat(f).isDone();
            assertThat(f.join()).isEqualTo(new GenerationOutcome.Failure(FailureKind.INVALID_OUTPUT, 0L));
            assertThat(g.run(seekerTask())).isEqualTo(new GenerationOutcome.Failure(FailureKind.INVALID_OUTPUT, 0L));
        }
        assertThat(executed.get()).as("nothing submitted to the executor").isZero();
        verifyNoInteractions(assistant, repo, gate, groundingCheck);
        assertThat(circuit.consecutiveFailures()).isZero();
        assertThat(circuit.lastSuccessAt()).as("no circuit bookkeeping").isNull();
        assertThat(seekerTimer("invalid_output")).isEqualTo(4);
        assertThat(meters.find("talentmatch.ai.explanations").tag("audience", "recruiter").timers()).isEmpty();
    }

    @Test
    void seekerInfoLogLineCarriesTheSeekerAudienceAndNoText(CapturedOutput out) {
        seekerPersistReturns(1);
        when(groundingCheck.accept(any(), any(), any(), any())).thenReturn(true);
        when(seekerAssistant.explain(any())).thenReturn(result(seekerExplanation(), FinishReason.STOP));
        seekerGenerator().run(seekerTask());
        String line = out.getAll().lines().filter(l -> l.contains("AI explanation job=")).findFirst().orElseThrow();
        assertThat(line).contains("INFO").contains("candidate=" + row.candidateId() + " audience=seeker provider=ollama")
                .contains("outcome=success").contains("inputTokens=123");
        assertThat(out.getAll()).doesNotContain(seekerExplanation().explanation())
                .doesNotContain(seekerExplanation().headline()).doesNotContain("Zelda")
                .doesNotContain("Secret candidate summary").doesNotContain("PROMPT ");
    }

    @Test
    void seekerGuardMissIsLoggedWithTheAudience(CapturedOutput out) {
        seekerPersistReturns(0);
        when(groundingCheck.accept(any(), any(), any(), any())).thenReturn(true);
        when(seekerAssistant.explain(any())).thenReturn(result(seekerExplanation(), FinishReason.STOP));
        GenerationOutcome o = seekerGenerator().run(seekerTask());
        assertThat(((GenerationOutcome.Success) o).persisted()).isFalse();
        assertThat(out.getAll()).contains("AI seeker explanation for candidate " + row.candidateId() + " / job "
                + job.jobId() + " not stored").doesNotContain(seekerExplanation().explanation());
    }

    @Test
    void timerCarriesTheAudienceTagForEveryOutcome() {
        persistReturns(1);
        org.mockito.Mockito.doReturn(result(validExplanation(), FinishReason.STOP)).when(assistant).explain(any());
        generator().run(task);
        org.mockito.Mockito.doThrow(new IllegalStateException("down")).when(assistant).explain(any());
        generator().run(task);
        generator(r -> { throw new java.util.concurrent.RejectedExecutionException("full"); }).submit(task);

        var timers = meters.find("talentmatch.ai.explanations").timers();
        assertThat(timers).hasSize(3).allSatisfy(t -> {
            assertThat(t.getId().getTag("audience")).isEqualTo("recruiter");
            assertThat(t.getId().getTag("provider")).isEqualTo("ollama");
            assertThat(t.getId().getTag("model")).isEqualTo("qwen2.5:7b-instruct");
        });
        assertThat(meters.find("talentmatch.ai.explanations").tag("audience", "recruiter").tag("outcome", "success")
                .timer().count()).isEqualTo(1);
        assertThat(meters.find("talentmatch.ai.explanations").tag("audience", "recruiter")
                .tag("outcome", "provider_error").timer().count()).isEqualTo(1);
        assertThat(meters.find("talentmatch.ai.explanations").tag("audience", "recruiter").tag("outcome", "rejected")
                .timer().count()).isEqualTo(1);
    }

    @Test
    void localModelBusyRejectionIsTaggedAndLogged(CapturedOutput out) {
        LocalModelGate gate = mock(LocalModelGate.class);
        when(gate.tryAcquireShared()).thenReturn(false);
        ExplanationGenerator g = new ExplanationGenerator(assistant, new MatchExplanationValidator(), repo, circuit,
                model, Runnable::run, clock, meters, gate);
        assertThat(g.run(task)).isInstanceOf(GenerationOutcome.Rejected.class);
        verifyNoInteractions(assistant, repo);
        verify(gate, never()).releaseShared();
        assertThat(meters.find("talentmatch.ai.explanations").tag("audience", "recruiter").tag("outcome", "rejected")
                .timer().count()).isEqualTo(1);
        assertThat(out.getAll()).contains("audience=recruiter outcome=local_model_busy");
    }

    @Test
    void infoLogLineCarriesTheRecruiterAudience(CapturedOutput out) {
        persistReturns(1);
        org.mockito.Mockito.doReturn(result(validExplanation(), FinishReason.STOP)).when(assistant).explain(any());
        generator().run(task);
        String line = out.getAll().lines().filter(l -> l.contains("AI explanation job=")).findFirst().orElseThrow();
        assertThat(line).contains("candidate=" + row.candidateId() + " audience=recruiter provider=ollama");
    }

    @Test
    void guardMissLogNamesTheAudienceButNoText(CapturedOutput out) {
        persistReturns(0);
        org.mockito.Mockito.doReturn(result(validExplanation(), FinishReason.STOP)).when(assistant).explain(any());
        generator().run(task);
        assertThat(out.getAll()).contains("AI recruiter explanation for candidate " + row.candidateId())
                .doesNotContain(validExplanation().explanation());
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
