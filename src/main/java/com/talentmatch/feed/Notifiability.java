package com.talentmatch.feed;

import java.time.Duration;
import java.time.Instant;

/**
 * The notifiable rule (§4.8, decision g), pure. A feed job gets a notification row only when it is
 * open, not baseline, first seen inside the fresh window, passes the preferences, has an owner score
 * (an owner profile exists and the job is matchable) of at least the threshold, notifications are
 * enabled, the channel is configured, and no notification exists for it yet.
 */
public final class Notifiability {

    /** Tolerance for comparing a score with the threshold (3/5 must count as 0.6). */
    static final double SCORE_EPSILON = 1e-9;

    /** Why a job is not notified; checked in this order, the first failing rule wins. */
    public enum Reason {
        CLOSED,
        BASELINE,
        NOT_FRESH,
        FILTERED,
        NOT_SCORED,
        BELOW_THRESHOLD,
        DISABLED,
        ALREADY_NOTIFIED,
        /** Everything else holds, but the channel can't deliver: logged, no row (§4.8). */
        CHANNEL_NOT_CONFIGURED
    }

    /** {@code reason} is null exactly when {@code shouldNotify} is true. */
    public record Decision(boolean shouldNotify, Reason reason) {

        static final Decision NOTIFY = new Decision(true, null);

        static Decision skip(Reason reason) {
            return new Decision(false, reason);
        }
    }

    /**
     * @param closedAt          feed_job.closed_at (null = open)
     * @param baseline          feed_job.baseline
     * @param firstSeenAt       feed_job.first_seen_at
     * @param preferencesPass   the preference verdict is PASS
     * @param score             the owner's score; null when there is no owner profile or the job is
     *                          not matchable
     * @param enabled           notification_settings.enabled
     * @param minScore          notification_settings.min_score
     * @param channelConfigured the settings' channel can deliver
     * @param alreadyNotified   a feed_notification row exists for the job
     */
    public record Input(Instant closedAt, boolean baseline, Instant firstSeenAt, boolean preferencesPass, Double score,
                        boolean enabled, double minScore, boolean channelConfigured, boolean alreadyNotified) {
    }

    private Notifiability() {
    }

    public static Decision decide(Input in, Instant now, Duration freshWindow) {
        if (in.closedAt() != null) {
            return Decision.skip(Reason.CLOSED);
        }
        if (in.baseline()) {
            return Decision.skip(Reason.BASELINE);
        }
        if (in.firstSeenAt() == null || !in.firstSeenAt().isAfter(now.minus(freshWindow))) {
            return Decision.skip(Reason.NOT_FRESH);
        }
        if (!in.preferencesPass()) {
            return Decision.skip(Reason.FILTERED);
        }
        if (in.score() == null || in.score().isNaN()) {
            return Decision.skip(Reason.NOT_SCORED);
        }
        if (in.score() + SCORE_EPSILON < in.minScore()) {
            return Decision.skip(Reason.BELOW_THRESHOLD);
        }
        if (!in.enabled()) {
            return Decision.skip(Reason.DISABLED);
        }
        if (in.alreadyNotified()) {
            return Decision.skip(Reason.ALREADY_NOTIFIED);
        }
        if (!in.channelConfigured()) {
            return Decision.skip(Reason.CHANNEL_NOT_CONFIGURED);
        }
        return Decision.NOTIFY;
    }
}
