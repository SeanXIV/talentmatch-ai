package com.talentmatch.feed;

import java.time.Instant;

/**
 * The feed's state for {@code GET /api/feed/status} (§5.2). Step 6 filled the poller's part; step 7
 * adds the profile and preferences the processor works against. The notification, aggregator,
 * enrichment and detection parts join with later steps.
 *
 * @param enabled          {@code talentmatch.feed.enabled}
 * @param schedulerEnabled the scheduler polls (and the processor runs) on its own: the feed and its
 *                         scheduler are enabled
 */
public record FeedStatusView(boolean enabled, boolean schedulerEnabled, Profile profile, Preferences preferences,
                             Sources sources, Processing processing) {

    /**
     * @param present        an owner profile was confirmed
     * @param version        its current version (null without a profile)
     * @param appliedVersion the version the open feed jobs were last queued for re-scoring against
     *                       ({@code feed_state}); differs from {@code version} until the processor
     *                       catches up
     */
    public record Profile(boolean present, Integer version, Integer appliedVersion) {
    }

    /**
     * @param present preferences were saved (otherwise the feed is not filtered)
     * @param version their current version (null without preferences)
     */
    public record Preferences(boolean present, Integer version) {
    }

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
