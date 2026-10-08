package com.talentmatch.feed;

import java.time.Instant;

/**
 * The feed's state for {@code GET /api/feed/status} (§5.2). Step 6 fills the poller's part; the
 * profile, preferences, notification, aggregator, enrichment and detection parts join with later
 * steps.
 *
 * @param enabled          {@code talentmatch.feed.enabled}
 * @param schedulerEnabled the scheduler polls on its own (the feed and its scheduler are enabled)
 */
public record FeedStatusView(boolean enabled, boolean schedulerEnabled, Sources sources, Processing processing) {

    /**
     * @param failing       ACTIVE sources whose last poll failed ({@code consecutive_failures > 0})
     * @param lastSuccessAt the latest successful poll of any source
     */
    public record Sources(long total, long active, long failing, Instant lastSuccessAt) {
    }

    /** @param pending feed jobs waiting for the processor ({@code process_after} set) */
    public record Processing(long pending) {
    }
}
