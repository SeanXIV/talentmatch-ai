package com.talentmatch.feed;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Startup recovery for the feed (§6.3), single instance like {@code ProfileRecovery}. Step 6 covers
 * the poller: every lease is dropped, since a lease that survived a restart belonged to a poll that
 * no longer runs (the source is due again at once instead of after the lease expires). Enrichment,
 * notification and version checks join with later steps. Each step logs class names only.
 */
@Component
public class FeedRecovery {

    private static final Logger log = LoggerFactory.getLogger(FeedRecovery.class);

    private final FeedSourceRepository sources;

    public FeedRecovery(FeedSourceRepository sources) {
        this.sources = sources;
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
    }
}
