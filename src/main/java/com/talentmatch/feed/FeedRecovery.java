package com.talentmatch.feed;

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
 *   <li>The profile, preferences and skill vocabulary are compared with what the open jobs were last
 *       processed for (§4.11); a difference marks them for processing.</li>
 *   <li>The processor is woken (a no-op while the scheduler is off).</li>
 * </ol>
 * Enrichment and notification recovery join with later steps. Each step runs on its own and logs
 * class names only.
 */
@Component
public class FeedRecovery {

    private static final Logger log = LoggerFactory.getLogger(FeedRecovery.class);

    private final FeedSourceRepository sources;
    private final FeedRefreshService refresh;
    private final FeedProcessor processor;

    public FeedRecovery(FeedSourceRepository sources, FeedRefreshService refresh, FeedProcessor processor) {
        this.sources = sources;
        this.refresh = refresh;
        this.processor = processor;
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
            refresh.reconcile();
        } catch (RuntimeException e) {
            log.warn("Feed recovery: could not compare the applied versions ({}); the processor retries",
                    SourcePoller.describe(e));
        }
        processor.wake();
    }
}
