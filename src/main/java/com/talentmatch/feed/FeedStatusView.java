package com.talentmatch.feed;

import com.talentmatch.notify.Channel;
import java.time.Instant;

/**
 * The feed's state for {@code GET /api/feed/status} (§5.2). Step 6 filled the poller's part; step 7
 * adds the profile and preferences the processor works against; step 8 the notifications; step 9
 * the aggregator. The enrichment and detection parts join with later steps.
 *
 * @param enabled          {@code talentmatch.feed.enabled}
 * @param schedulerEnabled the scheduler polls (and the processor runs) on its own: the feed and its
 *                         scheduler are enabled
 */
public record FeedStatusView(boolean enabled, boolean schedulerEnabled, Profile profile, Preferences preferences,
                             Notifications notifications, Sources sources, Aggregator aggregator,
                             Processing processing) {

    /**
     * Notification delivery (§5.2, step 8). {@code paused}, {@code pausedUntil} and {@code lastError} are
     * additions to §5.2 (D20).
     *
     * @param configured    the channel can deliver (for email: host, from and to are set)
     * @param pending       PENDING + SENDING rows
     * @param failedLast24h FAILED rows changed in the last 24 hours (database clock), expired ones included
     * @param paused        the dispatcher is paused after a configuration error
     * @param lastError     the sanitized text of the last failed send (null after a success)
     */
    public record Notifications(boolean enabled, Channel channel, boolean configured, double minScore, long pending,
                                long failedLast24h, boolean paused, Instant pausedUntil, String lastError) {
    }

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

    /**
     * Adzuna (§5.2).
     *
     * @param configured    {@code ADZUNA_APP_ID} and {@code ADZUNA_APP_KEY} are set
     * @param requestsToday requests counted against today's (UTC) budget, probes included
     * @param dailyBudget   {@code talentmatch.feed.adzuna.daily-request-budget}
     */
    public record Aggregator(boolean configured, int requestsToday, int dailyBudget) {
    }

    /** @param pending feed jobs waiting for the processor ({@code process_after} set) */
    public record Processing(long pending) {
    }
}
