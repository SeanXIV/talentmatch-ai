package com.talentmatch.ai;

import static com.talentmatch.ai.AiFixtures.ada;
import static com.talentmatch.ai.AiFixtures.job;
import static com.talentmatch.ai.AiFixtures.match;
import static com.talentmatch.ai.AiFixtures.props;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.talentmatch.ai.ExplanationGenerator.GenerationTask;
import com.talentmatch.repository.MatchJdbcRepository.StoredExplanation;
import com.talentmatch.support.MutableClock;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** Spec §4 ExplanationService.explain algorithm / §5 single-flight / §10 unit 7. */
class ExplanationServiceTest {

    private static final String STORED_PAYLOAD = """
            {"headline":"Stored headline","explanation":"Stored AI text about Ada and Java.",
             "strengths":["Java"],"gaps":["Kubernetes"]}""";

    private final MutableClock clock = MutableClock.ticking();
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final ExplanationGenerator generator = mock(ExplanationGenerator.class);
    private final JobContext job = job("Build APIs.");

    private AiCircuitBreaker circuit = new AiCircuitBreaker(props(), clock);

    private ExplanationService service(AiProperties p) {
        circuit = new AiCircuitBreaker(p, clock);
        return new ExplanationService(p, generator, circuit, clock, meters);
    }

    private ExplanationService service() {
        return service(props(true, 3, Duration.ofSeconds(2), Duration.ofSeconds(60)));
    }

    private static List<MatchContext> rows(int n) {
        List<MatchContext> out = new ArrayList<>();
        for (int i = 1; i <= n; i++) {
            out.add(match(i, "Cand " + i));
        }
        return out;
    }

    private String hashFor(MatchContext row) {
        return ExplanationInputHasher.hash(new ExplanationPromptBuilder(2000).build(job, row));
    }

    private MatchContext withStored(MatchContext row, boolean fresh, String payload) {
        StoredExplanation s = new StoredExplanation("Stored AI text about Ada and Java.", payload,
                fresh ? hashFor(row) : "0".repeat(64), "ollama/qwen2.5:7b-instruct", AiFixtures.T0);
        return new MatchContext(row.rank(), row.candidateId(), row.candidateName(), row.candidateSummary(),
                row.candidateUpdatedAt(), row.storedScore(), row.scorePercent(), row.evaluation(), s);
    }

    private static GenerationOutcome.Success success(String text) {
        return new GenerationOutcome.Success(new MatchExplanation("New headline", text, List.of("Java"), List.of()),
                "ollama/qwen2.5:7b-instruct", AiFixtures.T0.plusSeconds(5), 10, true);
    }

    private static CompletableFuture<GenerationOutcome> done(GenerationOutcome o) {
        return CompletableFuture.completedFuture(o);
    }

    private static void assertInvariant(ExplanationBatch batch) {
        batch.byCandidate().values().forEach(r -> {
            assertThat(r.explanation()).isNotNull();
            boolean ready = r.status() == ExplanationStatus.READY;
            assertThat(r.aiExplanation() != null).isEqualTo(ready);
            assertThat(r.explanation().source() == ExplanationSource.AI).isEqualTo(ready);
            if (ready) {
                assertThat(r.aiExplanation()).isEqualTo(r.explanation().text());
                assertThat(r.explanation().reason()).isNull();
                assertThat(r.explanation().note()).isNull();
            } else {
                assertThat(r.explanation().reason()).isNotNull();
                assertThat(r.explanation().note()).isNotBlank();
            }
        });
    }

    private static ExplanationResult of(ExplanationBatch b, MatchContext row) {
        return b.byCandidate().get(row.candidateId());
    }

    // ------------------------------------------------------------------ disabled / top N / fresh

    @Test
    void disabledNeverTouchesTheGeneratorAndRendersAiDisabledTemplates() {
        List<MatchContext> rows = rows(4);
        rows.set(0, withStored(rows.get(0), true, STORED_PAYLOAD));
        ExplanationService s = service(props(false, 3, Duration.ofSeconds(2), Duration.ofSeconds(60)));
        ExplanationBatch b = s.explain(job, rows, true);
        verifyNoInteractions(generator);
        assertThat(s.aiActive()).isFalse();
        assertThat(b.generated()).isZero();
        assertThat(b.byCandidate()).hasSize(4);
        b.byCandidate().values().forEach(r -> {
            assertThat(r.status()).isEqualTo(ExplanationStatus.UNAVAILABLE);
            assertThat(r.explanation().reason()).isEqualTo(ExplanationReason.AI_DISABLED);
            assertThat(r.explanation().note()).doesNotStartWith("The previous");
        });
        assertInvariant(b);

        // the null-generator constructor form behaves the same
        ExplanationService nullGen = new ExplanationService(props(true, 3, Duration.ofSeconds(2),
                Duration.ofSeconds(60)), null, circuit, clock, null);
        assertThat(nullGen.aiActive()).isFalse();
        assertThat(of(nullGen.explain(job, rows, false), rows.get(1)).explanation().reason())
                .isEqualTo(ExplanationReason.AI_DISABLED);
    }

    @Test
    void onlyTopNAreGeneratedAndOthersGetNotInTopN() {
        when(generator.submit(any())).thenAnswer(inv -> done(success("Generated text about Java skills here.")));
        List<MatchContext> rows = rows(5);
        rows.set(4, withStored(rows.get(4), false, STORED_PAYLOAD)); // rank 5, outdated stored text
        ExplanationBatch b = service().explain(job, rows, false);

        verify(generator, times(3)).submit(any());
        assertThat(b.generated()).isEqualTo(3);
        for (int i = 0; i < 3; i++) {
            ExplanationResult r = of(b, rows.get(i));
            assertThat(r.status()).isEqualTo(ExplanationStatus.READY);
            assertThat(r.explanation().model()).isEqualTo("ollama/qwen2.5:7b-instruct");
            assertThat(r.explanation().generatedAt()).isEqualTo(AiFixtures.T0.plusSeconds(5));
            assertThat(r.explanation().headline()).isEqualTo("New headline");
        }
        ExplanationResult fourth = of(b, rows.get(3));
        assertThat(fourth.status()).isEqualTo(ExplanationStatus.UNAVAILABLE);
        assertThat(fourth.explanation().reason()).isEqualTo(ExplanationReason.NOT_IN_TOP_N);
        assertThat(fourth.explanation().note()).contains("top 3 matches");
        ExplanationResult fifth = of(b, rows.get(4));
        assertThat(fifth.status()).isEqualTo(ExplanationStatus.STALE);
        assertThat(fifth.explanation().reason()).isEqualTo(ExplanationReason.NOT_IN_TOP_N);
        assertThat(fifth.explanation().note()).startsWith(ExplanationReason.STALE_PREFIX);
        assertInvariant(b);
    }

    @Test
    void taskCarriesTheExactPromptAndItsHash() {
        when(generator.submit(any())).thenAnswer(inv -> done(success("Generated text about Java skills here.")));
        MatchContext row = match(1, "Ada Lovelace");
        service().explain(job, List.of(row), false);
        ArgumentCaptor<GenerationTask> task = ArgumentCaptor.forClass(GenerationTask.class);
        verify(generator).submit(task.capture());
        assertThat(task.getValue().prompt()).isEqualTo(new ExplanationPromptBuilder(2000).build(job, row));
        assertThat(task.getValue().inputHash()).isEqualTo(hashFor(row));
        assertThat(task.getValue().match()).isSameAs(row);
        assertThat(task.getValue().job()).isSameAs(job);
    }

    @Test
    void freshStoredExplanationIsReadyWithoutACall() {
        MatchContext row = withStored(match(1, "Ada"), true, STORED_PAYLOAD);
        MatchContext beyond = withStored(match(4, "Bo"), true, STORED_PAYLOAD);
        ExplanationBatch b = service().explain(job, List.of(row, beyond), false);
        verifyNoInteractions(generator);
        assertThat(b.generated()).isZero();
        for (MatchContext m : List.of(row, beyond)) {
            ExplanationResult r = of(b, m);
            assertThat(r.status()).isEqualTo(ExplanationStatus.READY);
            assertThat(r.aiExplanation()).isEqualTo("Stored AI text about Ada and Java.");
            assertThat(r.explanation().headline()).isEqualTo("Stored headline");
            assertThat(r.explanation().strengths()).containsExactly("Java");
            assertThat(r.explanation().gaps()).containsExactly("Kubernetes");
            assertThat(r.explanation().model()).isEqualTo("ollama/qwen2.5:7b-instruct");
            assertThat(r.explanation().generatedAt()).isEqualTo(AiFixtures.T0);
        }
        assertInvariant(b);
    }

    @Test
    void unreadableStoredPayloadIsTreatedAsAbsent() {
        org.mockito.Mockito.doReturn(done(new GenerationOutcome.Failure(FailureKind.INVALID_OUTPUT, 1))).when(generator).submit(any());
        MatchContext row = withStored(match(1, "Ada"), true, "not json");
        ExplanationResult r = of(service().explain(job, List.of(row), false), row);
        verify(generator).submit(any());
        assertThat(r.status()).as("no usable stored explanation").isEqualTo(ExplanationStatus.UNAVAILABLE);
        assertThat(r.explanation().reason()).isEqualTo(ExplanationReason.GENERATION_FAILED);
    }

    // ------------------------------------------------------------------ regenerate

    @Test
    void regenerateWithFreshStoredAndFailureKeepsTheOldExplanation() {
        org.mockito.Mockito.doReturn(done(new GenerationOutcome.Failure(FailureKind.PROVIDER_ERROR, 1))).when(generator).submit(any());
        MatchContext row = withStored(match(1, "Ada"), true, STORED_PAYLOAD);
        MatchContext beyond = withStored(match(4, "Bo"), true, STORED_PAYLOAD);
        ExplanationBatch b = service().explain(job, List.of(row, beyond), true);
        verify(generator, times(1)).submit(any());
        assertThat(of(b, row).status()).isEqualTo(ExplanationStatus.READY);
        assertThat(of(b, row).aiExplanation()).isEqualTo("Stored AI text about Ada and Java.");
        assertThat(of(b, beyond).status()).as("rank > topN keeps its fresh text").isEqualTo(ExplanationStatus.READY);
        assertThat(b.generated()).isZero();
        assertInvariant(b);
    }

    @Test
    void regenerateWithFreshStoredAndSuccessReturnsTheNewText() {
        org.mockito.Mockito.doReturn(done(success("Brand new AI text about Java skills."))).when(generator).submit(any());
        MatchContext row = withStored(match(1, "Ada"), true, STORED_PAYLOAD);
        ExplanationBatch b = service().explain(job, List.of(row), true);
        assertThat(of(b, row).aiExplanation()).isEqualTo("Brand new AI text about Java skills.");
        assertThat(b.generated()).isEqualTo(1);
    }

    @Test
    void regenerateWithFreshStoredAndBudgetExceededKeepsTheOldExplanation() {
        org.mockito.Mockito.doReturn(new CompletableFuture<>()).when(generator).submit(any());
        MatchContext row = withStored(match(1, "Ada"), true, STORED_PAYLOAD);
        ExplanationService s = service(props(true, 3, Duration.ZERO, Duration.ofSeconds(60)));
        ExplanationResult r = of(s.explain(job, List.of(row), true), row);
        assertThat(r.status()).isEqualTo(ExplanationStatus.READY);
        assertThat(r.aiExplanation()).isEqualTo("Stored AI text about Ada and Java.");
    }

    // ------------------------------------------------------------------ budget / pending

    @Test
    void budgetExceededGivesPendingWithinTheBudget() {
        org.mockito.Mockito.doReturn(new CompletableFuture<>()).when(generator).submit(any());
        List<MatchContext> rows = rows(3);
        rows.set(2, withStored(rows.get(2), false, STORED_PAYLOAD));
        ExplanationService s = service(props(true, 3, Duration.ofMillis(300), Duration.ofSeconds(60)));
        long t0 = System.nanoTime();
        ExplanationBatch b = s.explain(job, rows, false);
        long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
        assertThat(ms).as("whole page waits one shared budget").isBetween(250L, 1_500L);
        b.byCandidate().values().forEach(r -> {
            assertThat(r.status()).isEqualTo(ExplanationStatus.PENDING);
            assertThat(r.explanation().reason()).isEqualTo(ExplanationReason.GENERATING);
            assertThat(r.explanation().note()).isEqualTo(ExplanationReason.GENERATING.note(3));
        });
        assertThat(b.generated()).isZero();
        assertInvariant(b);
    }

    @Test
    void zeroBudgetNeverWaits() {
        org.mockito.Mockito.doReturn(new CompletableFuture<>()).when(generator).submit(any());
        ExplanationService s = service(props(true, 3, Duration.ZERO, Duration.ofSeconds(60)));
        long t0 = System.nanoTime();
        ExplanationBatch b = s.explain(job, rows(3), false);
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0)).isLessThan(500);
        assertThat(b.byCandidate().values()).allMatch(r -> r.status() == ExplanationStatus.PENDING);
    }

    // ------------------------------------------------------------------ single-flight

    @Test
    void concurrentExplainsShareOneGeneratorCallPerRow() throws Exception {
        CompletableFuture<GenerationOutcome> slow = new CompletableFuture<>();
        org.mockito.Mockito.doReturn(slow).when(generator).submit(any());
        ExplanationService s = service(props(true, 3, Duration.ofSeconds(3), Duration.ofSeconds(60)));
        MatchContext row = match(1, "Ada");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<ExplanationBatch> a = pool.submit(() -> { start.await(); return s.explain(job, List.of(row), false); });
            Future<ExplanationBatch> b = pool.submit(() -> { start.await(); return s.explain(job, List.of(row), false); });
            start.countDown();
            Thread.sleep(300);
            slow.complete(success("Shared AI text about the Java skill."));
            ExplanationBatch ra = a.get(5, TimeUnit.SECONDS);
            ExplanationBatch rb = b.get(5, TimeUnit.SECONDS);
            verify(generator, times(1)).submit(any());
            assertThat(of(ra, row).aiExplanation()).isEqualTo("Shared AI text about the Java skill.");
            assertThat(of(rb, row).aiExplanation()).isEqualTo("Shared AI text about the Java skill.");
            assertThat(ra.generated() + rb.generated()).as("only the starter counts it").isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void inFlightIsJoinedEvenWithRegenerateAndRecentSuccessIsReusedUnlessRegenerate() {
        CompletableFuture<GenerationOutcome> slow = new CompletableFuture<>();
        org.mockito.Mockito.doReturn(slow).when(generator).submit(any());
        ExplanationService s = service(props(true, 3, Duration.ZERO, Duration.ofSeconds(60)));
        MatchContext row = match(1, "Ada");
        assertThat(of(s.explain(job, List.of(row), false), row).status()).isEqualTo(ExplanationStatus.PENDING);
        assertThat(of(s.explain(job, List.of(row), true), row).status()).isEqualTo(ExplanationStatus.PENDING);
        verify(generator, times(1)).submit(any());

        slow.complete(success("Finished AI text about the Java skill."));
        ExplanationBatch reused = s.explain(job, List.of(row), false); // DB not yet showing it
        verify(generator, times(1)).submit(any());
        assertThat(of(reused, row).status()).isEqualTo(ExplanationStatus.READY);
        assertThat(of(reused, row).aiExplanation()).isEqualTo("Finished AI text about the Java skill.");
        assertThat(reused.generated()).isZero();

        org.mockito.Mockito.doReturn(done(success("Regenerated text about Java."))).when(generator).submit(any());
        s.explain(job, List.of(row), true);
        verify(generator, times(2)).submit(any());

        // recent-success cache expires after 2 minutes
        clock.advance(Duration.ofMinutes(2).plusSeconds(1));
        s.explain(job, List.of(row), false);
        verify(generator, times(3)).submit(any());
    }

    // ------------------------------------------------------------------ failures, backoff, circuit

    @Test
    void providerFailureGivesProviderUnavailableAndBacksOffUntilRegenerateOrExpiry() {
        org.mockito.Mockito.doReturn(done(new GenerationOutcome.Failure(FailureKind.TIMEOUT, 1))).when(generator).submit(any());
        ExplanationService s = service();
        MatchContext row = match(1, "Ada");
        ExplanationResult first = of(s.explain(job, List.of(row), false), row);
        assertThat(first.status()).isEqualTo(ExplanationStatus.UNAVAILABLE);
        assertThat(first.explanation().reason()).isEqualTo(ExplanationReason.PROVIDER_UNAVAILABLE);

        ExplanationResult second = of(s.explain(job, List.of(row), false), row);
        verify(generator, times(1)).submit(any());
        assertThat(second.explanation().reason()).as("same reason while backing off")
                .isEqualTo(ExplanationReason.PROVIDER_UNAVAILABLE);

        s.explain(job, List.of(row), true);
        verify(generator, times(2)).submit(any());

        clock.advance(Duration.ofSeconds(61));
        s.explain(job, List.of(row), false);
        verify(generator, times(3)).submit(any());
    }

    @Test
    void invalidOrRefusedOutputGivesGenerationFailedAndBacksOff() {
        org.mockito.Mockito.doReturn(done(new GenerationOutcome.Failure(FailureKind.REFUSED, 1))).when(generator).submit(any());
        ExplanationService s = service();
        MatchContext row = match(1, "Ada");
        MatchContext stale = withStored(match(2, "Bo"), false, STORED_PAYLOAD);
        ExplanationBatch b = s.explain(job, List.of(row, stale), false);
        assertThat(of(b, row).explanation().reason()).isEqualTo(ExplanationReason.GENERATION_FAILED);
        assertThat(of(b, row).status()).isEqualTo(ExplanationStatus.UNAVAILABLE);
        assertThat(of(b, stale).status()).isEqualTo(ExplanationStatus.STALE);
        assertThat(of(b, stale).aiExplanation()).isNull();
        assertThat(of(b, stale).explanation().note()).startsWith(ExplanationReason.STALE_PREFIX);
        assertInvariant(b);

        ExplanationBatch again = s.explain(job, List.of(row, stale), false);
        verify(generator, times(2)).submit(any());
        assertThat(of(again, row).explanation().reason()).isEqualTo(ExplanationReason.GENERATION_FAILED);
        assertThat(of(again, stale).status()).isEqualTo(ExplanationStatus.STALE);
    }

    @Test
    void zeroBackoffRetriesImmediately() {
        org.mockito.Mockito.doReturn(done(new GenerationOutcome.Failure(FailureKind.INVALID_OUTPUT, 1))).when(generator).submit(any());
        ExplanationService s = service(props(true, 3, Duration.ofSeconds(1), Duration.ZERO));
        MatchContext row = match(1, "Ada");
        s.explain(job, List.of(row), false);
        s.explain(job, List.of(row), false);
        verify(generator, times(2)).submit(any());
    }

    @Test
    void openCircuitSkipsTheModelUnlessRegenerate() {
        ExplanationService s = service();
        circuit.recordFailure(FailureKind.PROVIDER_ERROR);
        circuit.recordFailure(FailureKind.PROVIDER_ERROR);
        circuit.recordFailure(FailureKind.PROVIDER_ERROR);
        List<MatchContext> rows = rows(3);
        ExplanationBatch b = s.explain(job, rows, false);
        verify(generator, never()).submit(any());
        assertThat(b.byCandidate().values()).allSatisfy(r -> {
            assertThat(r.status()).isEqualTo(ExplanationStatus.UNAVAILABLE);
            assertThat(r.explanation().reason()).isEqualTo(ExplanationReason.PROVIDER_UNAVAILABLE);
        });

        org.mockito.Mockito.doReturn(done(success("Regenerated text about Java."))).when(generator).submit(any());
        ExplanationBatch forced = s.explain(job, rows, true);
        verify(generator, times(3)).submit(any());
        assertThat(forced.byCandidate().values()).allMatch(r -> r.status() == ExplanationStatus.READY);
    }

    @Test
    void rejectionGivesAiBusyWithoutBackoff() {
        org.mockito.Mockito.doReturn(done(new GenerationOutcome.Rejected())).when(generator).submit(any());
        ExplanationService s = service();
        MatchContext row = match(1, "Ada");
        MatchContext stale = withStored(match(2, "Bo"), false, STORED_PAYLOAD);
        ExplanationBatch b = s.explain(job, List.of(row, stale), false);
        assertThat(of(b, row).status()).isEqualTo(ExplanationStatus.UNAVAILABLE);
        assertThat(of(b, row).explanation().reason()).isEqualTo(ExplanationReason.AI_BUSY);
        assertThat(of(b, stale).status()).isEqualTo(ExplanationStatus.STALE);
        assertThat(of(b, stale).explanation().reason()).isEqualTo(ExplanationReason.AI_BUSY);
        s.explain(job, List.of(row), false);
        verify(generator, times(3)).submit(any());
    }

    @Test
    void submitThrowingRejectedExecutionIsAiBusyAndOtherErrorsAreProviderUnavailable() {
        org.mockito.Mockito.doThrow(new RejectedExecutionException("full")).when(generator).submit(any());
        MatchContext row = match(1, "Ada");
        assertThat(of(service().explain(job, List.of(row), false), row).explanation().reason())
                .isEqualTo(ExplanationReason.AI_BUSY);

        org.mockito.Mockito.doThrow(new IllegalStateException("boom")).when(generator).submit(any());
        assertThat(of(service().explain(job, List.of(row), false), row).explanation().reason())
                .isEqualTo(ExplanationReason.PROVIDER_UNAVAILABLE);

        org.mockito.Mockito.doReturn(CompletableFuture.failedFuture(new IllegalStateException("x"))).when(generator).submit(any());
        assertThat(of(service().explain(job, List.of(row), false), row).explanation().reason())
                .isEqualTo(ExplanationReason.PROVIDER_UNAVAILABLE);
    }

    @Test
    void unexpectedExceptionInsideRendersTemplatesAndNeverThrows() {
        List<MatchContext> rows = rows(2);
        ExplanationBatch b = service().explain(null, rows, false); // NPE while building the prompt
        assertThat(b.byCandidate()).hasSize(2);
        b.byCandidate().values().forEach(r -> {
            assertThat(r.status()).isEqualTo(ExplanationStatus.UNAVAILABLE);
            assertThat(r.explanation().reason()).isEqualTo(ExplanationReason.GENERATION_FAILED);
        });
        verifyNoInteractions(generator);
        assertThat(service().explain(job, List.of(), false).byCandidate()).isEmpty();
        assertThat(service().explain(null, null, false).byCandidate()).isEmpty();
    }

    @Test
    void fallbacksAreCountedByReason() {
        ExplanationService s = service(props(false, 3, Duration.ofSeconds(1), Duration.ofSeconds(60)));
        s.explain(job, rows(2), false);
        assertThat(meters.get("talentmatch.ai.fallbacks").tag("reason", "AI_DISABLED").counter().count())
                .isEqualTo(2.0);
    }

    @Test
    void rowsAreKeyedByCandidateInRankOrder() {
        when(generator.submit(any())).thenAnswer(inv -> done(success("Generated text about Java skills here.")));
        List<MatchContext> rows = rows(4);
        ExplanationBatch b = service().explain(job, rows, false);
        assertThat(b.byCandidate().keySet()).containsExactlyInAnyOrderElementsOf(
                rows.stream().map(MatchContext::candidateId).toList());
        assertThat(b.byCandidate().get(UUID.randomUUID())).isNull();
        assertThat(ada()).isNotNull();
    }
    // ------------------------------------------------------------------ Phase 6 step 2: explainAll

    private static List<ExplanationRequest> requests(JobContext j, List<MatchContext> rows) {
        return rows.stream().map(r -> new ExplanationRequest(j, r)).toList();
    }

    private static JobContext otherJob() {
        return new JobContext(UUID.fromString("00000000-0000-0000-0000-00000000000b"), "Data Engineer", "Beta",
                "Pipelines.", AiFixtures.T0);
    }

    @Test
    void explainAllRejectsSeekerBeforeTouchingAnything() {
        List<ExplanationRequest> reqs = requests(job, rows(2));
        for (boolean enabled : List.of(true, false)) {
            ExplanationService s = service(props(enabled, 3, Duration.ofSeconds(1), Duration.ofSeconds(60)));
            org.assertj.core.api.Assertions.assertThatIllegalStateException()
                    .isThrownBy(() -> s.explainAll(Audience.SEEKER, reqs, false))
                    .withMessage("SEEKER is wired in step 3");
            org.assertj.core.api.Assertions.assertThatIllegalStateException()
                    .isThrownBy(() -> s.explainAll(Audience.SEEKER, List.of(), true));
        }
        verifyNoInteractions(generator);
        assertThat(circuit.consecutiveFailures()).isZero();
        assertThat(meters.find("talentmatch.ai.fallbacks").counters()).as("no seeker template rendered").isEmpty();
        org.assertj.core.api.Assertions.assertThatNullPointerException()
                .isThrownBy(() -> service().explainAll(null, reqs, false));
        org.assertj.core.api.Assertions.assertThatNullPointerException()
                .isThrownBy(() -> service().explainAll(Audience.RECRUITER, null, false));
    }

    @Test
    void explainAllReturnsResultsInInputOrderAndPicksTopNByRank() {
        when(generator.submit(any())).thenAnswer(inv -> {
            GenerationTask t = inv.getArgument(0);
            return done(success("Generated for " + t.match().candidateName() + " about Java."));
        });
        // input order deliberately differs from rank order
        List<MatchContext> rows = List.of(match(5, "E"), match(1, "A"), match(4, "D"), match(2, "B"), match(3, "C"));
        ExplanationResults r = service().explainAll(Audience.RECRUITER, requests(job, rows), false);

        assertThat(r.results()).hasSize(5);
        assertThat(r.generated()).isEqualTo(3);
        verify(generator, times(3)).submit(any());
        for (int i = 0; i < rows.size(); i++) {
            MatchContext row = rows.get(i);
            ExplanationResult res = r.results().get(i);
            if (row.rank() <= 3) {
                assertThat(res.status()).as("rank %d", row.rank()).isEqualTo(ExplanationStatus.READY);
                assertThat(res.aiExplanation()).isEqualTo("Generated for " + row.candidateName() + " about Java.");
            } else {
                assertThat(res.status()).as("rank %d", row.rank()).isEqualTo(ExplanationStatus.UNAVAILABLE);
                assertThat(res.explanation().reason()).isEqualTo(ExplanationReason.NOT_IN_TOP_N);
                assertThat(res.explanation().text()).contains(row.candidateName());
            }
        }
        ArgumentCaptor<GenerationTask> tasks = ArgumentCaptor.forClass(GenerationTask.class);
        verify(generator, times(3)).submit(tasks.capture());
        assertThat(tasks.getAllValues()).allSatisfy(t -> assertThat(t.audience()).isEqualTo(Audience.RECRUITER));
        assertThat(tasks.getAllValues()).extracting(t -> t.match().rank()).containsExactly(1, 2, 3);
    }

    @Test
    void explainAllResultsListIsImmutableAndEmptyInputIsEmpty() {
        ExplanationResults r = service().explainAll(Audience.RECRUITER, List.of(), false);
        assertThat(r.results()).isEmpty();
        assertThat(r.generated()).isZero();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> r.results().add(null))
                .isInstanceOf(UnsupportedOperationException.class);
        verifyNoInteractions(generator);
    }

    @Test
    void explainAllSpansJobsWithOneFlightPerPairAndTheRightJobInEachTask() {
        when(generator.submit(any())).thenAnswer(inv -> {
            GenerationTask t = inv.getArgument(0);
            return done(success("Generated for job " + t.job().title() + " about Java."));
        });
        MatchContext same = match(1, "Ada");
        JobContext other = otherJob();
        // the same candidate on two jobs (candidate side) plus a duplicate of the first pair
        List<ExplanationRequest> reqs = List.of(new ExplanationRequest(job, same),
                new ExplanationRequest(other, new MatchContext(2, same.candidateId(), same.candidateName(),
                        same.candidateSummary(), same.candidateUpdatedAt(), same.storedScore(), same.scorePercent(),
                        same.evaluation(), null)),
                new ExplanationRequest(job, same));
        ExplanationResults r = service().explainAll(Audience.RECRUITER, reqs, false);

        ArgumentCaptor<GenerationTask> tasks = ArgumentCaptor.forClass(GenerationTask.class);
        verify(generator, times(2)).submit(tasks.capture());
        assertThat(tasks.getAllValues()).extracting(t -> t.job().jobId())
                .containsExactly(job.jobId(), other.jobId());
        assertThat(tasks.getAllValues().get(1).prompt()).contains("Data Engineer").doesNotContain("Backend Engineer");
        assertThat(r.generated()).as("the duplicate joins the first flight").isEqualTo(2);
        assertThat(r.results()).extracting(ExplanationResult::aiExplanation).containsExactly(
                "Generated for job Backend Engineer about Java.",
                "Generated for job Data Engineer about Java.",
                "Generated for job Backend Engineer about Java.");
    }

    @Test
    void explainAllDisabledGivesAiDisabledTemplatesInOrderTaggedRecruiter() {
        List<MatchContext> rows = List.of(match(3, "C"), match(1, "A"), match(2, "B"));
        ExplanationService s = service(props(false, 3, Duration.ofSeconds(1), Duration.ofSeconds(60)));
        ExplanationResults r = s.explainAll(Audience.RECRUITER, requests(job, rows), true);
        verifyNoInteractions(generator);
        assertThat(r.generated()).isZero();
        for (int i = 0; i < rows.size(); i++) {
            assertThat(r.results().get(i).status()).isEqualTo(ExplanationStatus.UNAVAILABLE);
            assertThat(r.results().get(i).explanation().reason()).isEqualTo(ExplanationReason.AI_DISABLED);
            assertThat(r.results().get(i).explanation().text()).contains(rows.get(i).candidateName());
        }
        assertThat(meters.get("talentmatch.ai.fallbacks").tag("audience", "recruiter").tag("reason", "AI_DISABLED")
                .counter().count()).isEqualTo(3.0);
    }

    @Test
    void explainAllBudgetElapsedGivesPendingGenerating() {
        org.mockito.Mockito.doReturn(new CompletableFuture<>()).when(generator).submit(any());
        ExplanationService s = service(props(true, 3, Duration.ofMillis(200), Duration.ofSeconds(60)));
        ExplanationResults r = s.explainAll(Audience.RECRUITER, requests(job, rows(2)), false);
        assertThat(r.results()).allSatisfy(res -> {
            assertThat(res.status()).isEqualTo(ExplanationStatus.PENDING);
            assertThat(res.explanation().reason()).isEqualTo(ExplanationReason.GENERATING);
        });
        assertThat(r.generated()).isZero();
    }

    @Test
    void explainAllUnexpectedErrorGivesGenerationFailedTemplatesInInputOrder() {
        when(generator.localModelBusy()).thenThrow(new IllegalStateException("boom"));
        List<MatchContext> rows = List.of(match(2, "B"), withStored(match(1, "A"), false, STORED_PAYLOAD));
        ExplanationResults r = service().explainAll(Audience.RECRUITER, requests(job, rows), false);
        assertThat(r.results()).hasSize(2);
        assertThat(r.results().get(0).status()).isEqualTo(ExplanationStatus.UNAVAILABLE);
        assertThat(r.results().get(0).explanation().text()).contains("B");
        assertThat(r.results().get(1).status()).isEqualTo(ExplanationStatus.STALE);
        assertThat(r.results()).allSatisfy(res ->
                assertThat(res.explanation().reason()).isEqualTo(ExplanationReason.GENERATION_FAILED));
        assertThat(r.generated()).isZero();
    }

    @Test
    void explainAllPreviousFlightOnTheSamePairIsJoinedAcrossCalls() {
        CompletableFuture<GenerationOutcome> slow = new CompletableFuture<>();
        org.mockito.Mockito.doReturn(slow).when(generator).submit(any());
        ExplanationService s = service(props(true, 3, Duration.ZERO, Duration.ofSeconds(60)));
        MatchContext row = match(1, "Ada");
        s.explainAll(Audience.RECRUITER, requests(job, List.of(row)), false);
        s.explain(job, List.of(row), false); // the wrapper shares the RECRUITER flight
        verify(generator, times(1)).submit(any());
        slow.complete(success("Shared text about Java."));
        assertThat(s.explainAll(Audience.RECRUITER, requests(job, List.of(row)), false).results().get(0)
                .aiExplanation()).isEqualTo("Shared text about Java.");
        verify(generator, times(1)).submit(any());
    }

    @Test
    void backoffIsPerPairNotPerCandidate() {
        org.mockito.Mockito.doReturn(done(new GenerationOutcome.Failure(FailureKind.TIMEOUT, 1)))
                .when(generator).submit(any());
        ExplanationService s = service();
        MatchContext row = match(1, "Ada");
        s.explainAll(Audience.RECRUITER, requests(job, List.of(row)), false);
        s.explainAll(Audience.RECRUITER, requests(job, List.of(row)), false); // backing off
        verify(generator, times(1)).submit(any());
        s.explainAll(Audience.RECRUITER, requests(otherJob(), List.of(row)), false); // other job: own key
        verify(generator, times(2)).submit(any());
    }

    @Test
    void flightKeyIncludesTheAudience() throws Exception {
        // Behaviour (two flights for RECRUITER and SEEKER on one pair, backoff per audience) is only
        // reachable once SEEKER is wired in step 3; until then pin the key's shape.
        Class<?> key = Class.forName("com.talentmatch.ai.ExplanationService$FlightKey");
        assertThat(key.isRecord()).isTrue();
        assertThat(java.util.Arrays.stream(key.getRecordComponents()).map(c -> c.getName() + ":"
                + c.getType().getSimpleName()).toList())
                .containsExactly("audience:Audience", "jobId:UUID", "candidateId:UUID", "inputHash:String");
    }

    @Test
    void fallbackCounterCarriesTheAudienceTag() {
        org.mockito.Mockito.doReturn(done(new GenerationOutcome.Rejected())).when(generator).submit(any());
        service().explain(job, rows(4), false);
        assertThat(meters.find("talentmatch.ai.fallbacks").counters()).isNotEmpty().allSatisfy(c ->
                assertThat(c.getId().getTag("audience")).isEqualTo("recruiter"));
        assertThat(meters.get("talentmatch.ai.fallbacks").tag("audience", "recruiter").tag("reason", "AI_BUSY")
                .counter().count()).isEqualTo(3.0);
        assertThat(meters.get("talentmatch.ai.fallbacks").tag("audience", "recruiter").tag("reason", "NOT_IN_TOP_N")
                .counter().count()).isEqualTo(1.0);
    }

    // ------------------------------------------------------------------ explain == explainAll(RECRUITER)

    /** One scenario: page rows, stub, budget, regenerate. Two fresh services see identical inputs. */
    private void assertWrapperEquivalent(String name, List<MatchContext> rows, Runnable stub, Duration budget,
                                         boolean regenerate, boolean enabled) {
        org.mockito.Mockito.reset(generator);
        stub.run();
        AiProperties p = props(enabled, 3, budget, Duration.ofSeconds(60));
        ExplanationBatch viaExplain = service(p).explain(job, rows, regenerate);
        org.mockito.Mockito.reset(generator);
        stub.run();
        ExplanationResults viaAll = service(p).explainAll(Audience.RECRUITER, requests(job, rows), regenerate);

        assertThat(viaExplain.generated()).as(name + " generated").isEqualTo(viaAll.generated());
        assertThat(viaExplain.byCandidate()).as(name + " size").hasSize(rows.size());
        for (int i = 0; i < rows.size(); i++) {
            assertThat(viaExplain.byCandidate().get(rows.get(i).candidateId())).as(name + " row " + i)
                    .isEqualTo(viaAll.results().get(i));
        }
    }

    @Test
    void explainIsExactlyExplainAllWithRecruiterAcrossScenarios() {
        List<MatchContext> mixed = new ArrayList<>(rows(5));
        mixed.set(0, withStored(mixed.get(0), true, STORED_PAYLOAD));
        mixed.set(1, withStored(mixed.get(1), false, STORED_PAYLOAD));
        mixed.set(4, withStored(mixed.get(4), false, STORED_PAYLOAD));
        Duration b = Duration.ofSeconds(1);

        assertWrapperEquivalent("disabled", mixed, () -> { }, b, false, false);
        assertWrapperEquivalent("success", mixed, () -> when(generator.submit(any()))
                .thenAnswer(inv -> done(success("Generated text about Java skills here."))), b, false, true);
        assertWrapperEquivalent("success+regenerate", mixed, () -> when(generator.submit(any()))
                .thenAnswer(inv -> done(success("Generated text about Java skills here."))), b, true, true);
        assertWrapperEquivalent("provider failure", mixed, () -> org.mockito.Mockito
                .doReturn(done(new GenerationOutcome.Failure(FailureKind.PROVIDER_ERROR, 1)))
                .when(generator).submit(any()), b, false, true);
        assertWrapperEquivalent("invalid output", mixed, () -> org.mockito.Mockito
                .doReturn(done(new GenerationOutcome.Failure(FailureKind.INVALID_OUTPUT, 1)))
                .when(generator).submit(any()), b, true, true);
        assertWrapperEquivalent("rejected", mixed, () -> org.mockito.Mockito
                .doReturn(done(new GenerationOutcome.Rejected())).when(generator).submit(any()), b, false, true);
        assertWrapperEquivalent("pending", mixed, () -> org.mockito.Mockito
                .doReturn(new CompletableFuture<>()).when(generator).submit(any()), Duration.ZERO, false, true);
        assertWrapperEquivalent("local model busy", mixed, () -> when(generator.localModelBusy()).thenReturn(true),
                b, false, true);
    }

    @Test
    void explainGoldenResultsAreUnchangedFromPhase3() {
        // A fixed page whose expected results are spelled out from the Phase 3 algorithm (spec §9.1 7).
        org.mockito.Mockito.doReturn(done(success("Generated text about Java skills here."))).when(generator)
                .submit(any());
        List<MatchContext> rows = new ArrayList<>(rows(5));
        rows.set(0, withStored(rows.get(0), true, STORED_PAYLOAD));  // fresh, top N   -> READY stored, no call
        rows.set(1, withStored(rows.get(1), false, STORED_PAYLOAD)); // stale, top N   -> READY generated
        rows.set(4, withStored(rows.get(4), false, STORED_PAYLOAD)); // stale, rank 5  -> STALE NOT_IN_TOP_N
        ExplanationBatch b = service().explain(job, rows, false);

        assertThat(b.generated()).isEqualTo(2);
        verify(generator, times(2)).submit(any());
        ExplanationResult r1 = of(b, rows.get(0));
        assertThat(r1).isEqualTo(ExplanationResult.ready(new ExplanationView(ExplanationSource.AI, "Stored headline",
                "Stored AI text about Ada and Java.", List.of("Java"), List.of("Kubernetes"),
                "ollama/qwen2.5:7b-instruct", AiFixtures.T0, null, null)));
        ExplanationResult generated = ExplanationResult.ready(new ExplanationView(ExplanationSource.AI, "New headline",
                "Generated text about Java skills here.", List.of("Java"), List.of(), "ollama/qwen2.5:7b-instruct",
                AiFixtures.T0.plusSeconds(5), null, null));
        assertThat(of(b, rows.get(1))).isEqualTo(generated);
        assertThat(of(b, rows.get(2))).isEqualTo(generated);
        assertThat(of(b, rows.get(3))).isEqualTo(ExplanationResult.template(ExplanationStatus.UNAVAILABLE,
                ExplanationFallbackRenderer.render("Cand 4", rows.get(3).scorePercent(), rows.get(3).evaluation(),
                        ExplanationReason.NOT_IN_TOP_N, false, 3)));
        assertThat(of(b, rows.get(4))).isEqualTo(ExplanationResult.template(ExplanationStatus.STALE,
                ExplanationFallbackRenderer.render("Cand 5", rows.get(4).scorePercent(), rows.get(4).evaluation(),
                        ExplanationReason.NOT_IN_TOP_N, true, 3)));
        assertInvariant(b);
    }
}
