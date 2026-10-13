package com.talentmatch.feed;

import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.talentmatch.notify.NotificationDispatcher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

/**
 * §6.3 step 2 and the step-10 deviation: RUNNING enrichments go back to PENDING; open SKIPPED jobs are
 * re-queued only when AI is available; a failing step doesn't stop the wake-ups. Collaborators mocked.
 */
class FeedRecoveryEnrichmentTest {

    private FeedSourceRepository sources;
    private FeedRefreshService refresh;
    private AggregatorQueries aggregatorQueries;
    private FeedProcessor processor;
    private NotificationDispatcher dispatcher;
    private FeedEnrichmentRepository rows;
    private JobSkillEnrichmentService enrichment;
    private FeedRecovery recovery;

    @BeforeEach
    void setUp() {
        sources = mock(FeedSourceRepository.class);
        refresh = mock(FeedRefreshService.class);
        aggregatorQueries = mock(AggregatorQueries.class);
        processor = mock(FeedProcessor.class);
        dispatcher = mock(NotificationDispatcher.class);
        rows = mock(FeedEnrichmentRepository.class);
        enrichment = mock(JobSkillEnrichmentService.class);
        recovery = new FeedRecovery(sources, refresh, aggregatorQueries, processor, dispatcher, rows, enrichment);
    }

    @Test
    void withAiOnRunningAndSkippedAreRequeuedThenTheStageIsWoken() {
        when(enrichment.aiEnabled()).thenReturn(true);
        recovery.recover();
        InOrder order = inOrder(rows, enrichment);
        order.verify(rows).resetRunning();
        order.verify(rows).resetSkipped();
        order.verify(enrichment).wake();
        verify(processor).wake();
        verify(dispatcher).wake();
    }

    @Test
    void withAiOffSkippedJobsStaySkipped() {
        when(enrichment.aiEnabled()).thenReturn(false);
        recovery.recover();
        verify(rows).resetRunning();
        verify(rows, never()).resetSkipped();
        verify(enrichment).wake();
    }

    @Test
    void aFailingEnrichmentResetDoesNotStopTheWakeUps() {
        when(rows.resetRunning()).thenThrow(new IllegalStateException("db down"));
        recovery.recover();
        verify(processor).wake();
        verify(dispatcher).wake();
        verify(enrichment).wake();
    }

    @Test
    void earlierStepsFailingStillResetEnrichments() {
        when(sources.releaseAllLeases()).thenThrow(new IllegalStateException("db down"));
        when(dispatcher.recoverInterrupted()).thenThrow(new IllegalStateException("db down"));
        when(enrichment.aiEnabled()).thenReturn(true);
        recovery.recover();
        verify(rows).resetRunning();
        verify(rows).resetSkipped();
    }

    @Test
    void enrichmentResetRunsAfterTheLeaseReleaseAndBeforeTheReconcile() {
        // L3: re-queued enrichments exist before reconcile marks jobs for processing
        when(enrichment.aiEnabled()).thenReturn(true);
        recovery.recover();
        InOrder order = inOrder(sources, rows, refresh, aggregatorQueries, dispatcher, processor, enrichment);
        order.verify(sources).releaseAllLeases();
        order.verify(rows).resetRunning();
        order.verify(rows).resetSkipped();
        order.verify(refresh).reconcile();
        order.verify(aggregatorQueries).reconcileSafely();
        order.verify(dispatcher).recoverInterrupted();
        order.verify(processor).wake();
        order.verify(dispatcher).wake();
        order.verify(enrichment).wake();
    }

    @Test
    void aFailingEnrichmentResetStillReconciles() {
        when(rows.resetRunning()).thenThrow(new IllegalStateException("db down"));
        recovery.recover();
        verify(refresh).reconcile();
        verify(aggregatorQueries).reconcileSafely();
    }
}
