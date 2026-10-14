package com.talentmatch.feed;

import com.talentmatch.notify.NotificationDispatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Startup recovery for the feed (§6.3), single instance like {@code ProfileRecovery}.
 * <ol>
 *   <li>Every lease is dropped: a lease that survived a restart belonged to a poll that no longer runs
 *       (the source is due again at once instead of after the lease expires).</li>
 *   <li>Enrichment: RUNNING rows (a call cut off by the shutdown) go back to PENDING, attempts kept; with
 *       AI available, open jobs SKIPPED while it was off (and having a description) go back to PENDING.</li>
 *   <li>The profile, preferences and skill vocabulary are compared with what the open jobs were last
 *       processed for (§4.11); a difference marks them for processing.</li>
 *   <li>The derived Adzuna searches are reconciled with the preferences and profile (they may have
 *       changed, or the keys been set, while the app was down).</li>
 *   <li>Notifications left SENDING by a crash go back to PENDING (at least once: they are sent again,
 *       with the same Message-ID).</li>
 *   <li>The processor, the notification dispatcher and the enrichment stage are woken (no-ops while the
 *       scheduler is off).</li>
 * </ol>
 * Each step runs on its own and logs class names only.
 */
@Component
public class FeedRecovery {

    private static final Logger log = LoggerFactory.getLogger(FeedRecovery.class);

    private final FeedSourceRepository sources;
    private final FeedRefreshService refresh;
    private final AggregatorQueries aggregatorQueries;
    private final FeedProcessor processor;
    private final NotificationDispatcher dispatcher;
    private final FeedEnrichmentRepository enrichmentRows;
    private final JobSkillEnrichmentService enrichment;

    public FeedRecovery(FeedSourceRepository sources, FeedRefreshService refresh, AggregatorQueries aggregatorQueries,
                        FeedProcessor processor, NotificationDispatcher dispatcher,
                        FeedEnrichmentRepository enrichmentRows, JobSkillEnrichmentService enrichment) {
        this.enrichmentRows = enrichmentRows;
        this.enrichment = enrichment;
        this.sources = sources;
        this.refresh = refresh;
        this.aggregatorQueries = aggregatorQueries;
        this.processor = processor;
        this.dispatcher = dispatcher;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recover() {
        try {
            int released = sources.releaseAllLeases();
            if (released > 0) {
                log.info("Feed recovery: released {} stale source lease(s)", released);
            }
        } catch (RuntimeException e) {
            log.warn("Feed recovery: could not release stale leases ({})", SourcePoller.describe(e));
        }
        try {
            int running = enrichmentRows.resetRunning();
            int skipped = enrichment.aiEnabled() ? enrichmentRows.resetSkipped() : 0;
            if (running + skipped > 0) {
                log.info("Feed recovery: {} interrupted and {} skipped enrichment(s) queued again", running, skipped);
            }
        } catch (RuntimeException e) {
            log.warn("Feed recovery: could not reset enrichments ({})", SourcePoller.describe(e));
        }
        try {
            refresh.reconcile();
        } catch (RuntimeException e) {
            log.warn("Feed recovery: could not compare the applied versions ({}); the processor retries",
                    SourcePoller.describe(e));
        }
        aggregatorQueries.reconcileSafely();
        try {
            int reset = dispatcher.recoverInterrupted();
            if (reset > 0) {
                log.info("Feed recovery: {} interrupted notification(s) will be sent again", reset);
            }
        } catch (RuntimeException e) {
            log.warn("Feed recovery: could not reset interrupted notifications ({})", SourcePoller.describe(e));
        }
        processor.wake();
        dispatcher.wake();
        enrichment.wake();
    }
}
