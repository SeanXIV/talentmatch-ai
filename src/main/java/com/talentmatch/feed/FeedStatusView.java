package com.talentmatch.feed;

import com.talentmatch.notify.Channel;
import java.time.Instant;

/**
 * The feed's state for {@code GET /api/feed/status} (§5.2). Step 6 filled the poller's part; step 7
 * adds the profile and preferences the processor works against; step 8 the notifications; step 9
 * the aggregator; step 10 the AI enrichment; step 11 the detection latency.
 *
 * @param enabled          {@code talentmatch.feed.enabled}
 * @param schedulerEnabled the scheduler polls (and the processor runs) on its own: the feed and its
 *                         scheduler are enabled
 */
public record FeedStatusView(boolean enabled, boolean schedulerEnabled, Profile profile, Preferences preferences,
                             Notifications notifications, Sources sources, Aggregator aggregator,
                             Processing processing, Enrichment enrichment, Detection detection) {

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

    /**
     * AI enrichment of open feed jobs (§5.2).
     *
     * @param failed    open jobs whose enrichment FAILED (they keep their dictionary skills)
     * @param modelBusy a CV extraction holds the local model right now
     * @param aiEnabled       a model exists and {@code talentmatch.feed.skills.enrichment.enabled} is true
     * @param pausedUntil     the stage is paused after a provider fault until then (null when not)
     * @param capReachedUntil the hourly cap stopped the last run until then (null when not)
     */
    public record Enrichment(long pending, long running, long failed, boolean modelBusy, boolean aiEnabled,
                             Instant pausedUntil, Instant capReachedUntil) {
    }

    /**
     * How fast new jobs reach the owner (§5.2): over the emails sent in the last {@code windowDays} days,
     * {@code sent_at − coalesce(posted_at, first_seen_at)}.
     *
     * @param p50LatencySeconds the median in whole seconds; null when nothing was sent
     * @param samples           the emails it was computed from
     */
    public record Detection(Long p50LatencySeconds, long samples, int windowDays) {
    }

    /** @param pending feed jobs waiting for the processor ({@code process_after} set) */
    public record Processing(long pending) {
    }
}
