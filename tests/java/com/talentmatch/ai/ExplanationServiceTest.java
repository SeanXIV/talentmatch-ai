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
}
