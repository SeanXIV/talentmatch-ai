package com.talentmatch.feed;

import com.talentmatch.notify.Channel;
import com.talentmatch.notify.NotificationChannels;
import com.talentmatch.notify.NotificationDispatcher;
import com.talentmatch.notify.NotificationRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.actuate.health.Status;
import org.springframework.stereotype.Component;

/**
 * Health component {@code feed} (§6.5): UP or DEGRADED, never DOWN, and not part of readiness. With
 * the configured status order DEGRADED never fails the overall health.
 * <ul>
 *   <li>UP {@code {enabled:false}} when the feed is switched off.</li>
 *   <li>DEGRADED when an ACTIVE source has failed 3 times in a row, or has been polled but has had no
 *       success for more than 3 × its interval (a silently dead source means missed jobs).</li>
 * </ul>
 * Also DEGRADED with reason {@code EMAIL_CONFIG} while email sending is paused after a configuration
 * error, and {@code NOTIFICATIONS_FAILED} when a notification failed in the last hour. Details hold
 * counts and source ids only: no URLs, tokens, keys or addresses.
 */
@Component("feedHealthIndicator")
public class FeedHealthIndicator implements HealthIndicator {

    private static final Logger log = LoggerFactory.getLogger(FeedHealthIndicator.class);

    /** The same custom status as the {@code ai} component (ordered after UP in application.yml). */
    public static final Status DEGRADED = new Status("DEGRADED");

    static final int FAILURE_THRESHOLD = 3;
    static final int STALE_INTERVALS = 3;
    static final int MAX_LISTED_IDS = 20;

    static final Duration FAILED_WINDOW = Duration.ofHours(1);

    private final FeedSourceRepository sources;
    private final NotificationDispatcher dispatcher;
    private final NotificationRepository notifications;
    private final NotificationChannels channels;
    private final FeedProperties properties;
    private final Clock clock;

    public FeedHealthIndicator(FeedSourceRepository sources, NotificationDispatcher dispatcher,
                               NotificationRepository notifications, NotificationChannels channels,
                               FeedProperties properties, Clock clock) {
        this.sources = sources;
        this.dispatcher = dispatcher;
        this.notifications = notifications;
        this.channels = channels;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public Health health() {
        if (!properties.enabled()) {
            return Health.up().withDetail("enabled", false).build();
        }
        List<FeedSource> active;
        try {
            active = sources.findActive();
        } catch (RuntimeException e) {
            // The database health component reports the outage; this one never turns DOWN.
            log.debug("Feed health: sources could not be read ({})", e.getClass().getName());
            return Health.unknown().withDetail("enabled", true).withDetail("error", "sources unavailable").build();
        }
        Instant now = clock.instant();
        List<UUID> failing = new ArrayList<>();
        List<UUID> stale = new ArrayList<>();
        for (FeedSource source : active) {
            if (source.consecutiveFailures() >= FAILURE_THRESHOLD) {
                failing.add(source.id());
            } else if (isStale(source, now)) {
                stale.add(source.id());
            }
        }
        List<String> reasons = new ArrayList<>();
        if (!failing.isEmpty()) {
            reasons.add("SOURCES_FAILING");
        }
        if (!stale.isEmpty()) {
            reasons.add("SOURCES_STALE");
        }
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("enabled", true);
        details.put("schedulerEnabled", properties.schedulerRunning());
        details.put("activeSources", active.size());
        details.put("failingSources", failing.size());
        details.put("staleSources", stale.size());
        details.put("failingSourceIds", ids(failing));
        details.put("staleSourceIds", ids(stale));
        details.put("notifications", notificationHealth(reasons));
        details.put("reasons", reasons);
        Health.Builder builder = reasons.isEmpty() ? Health.up() : Health.status(DEGRADED);
        return builder.withDetails(details).build();
    }

    /**
     * Email delivery: reason {@code EMAIL_CONFIG} while the dispatcher is paused after a configuration
     * error; {@code NOTIFICATIONS_FAILED} when a notification failed in the last hour (rows that expired
     * unsent are not counted, D8). Never opens an SMTP connection; a database error only drops the count.
     */
    private Map<String, Object> notificationHealth(List<String> reasons) {
        NotificationDispatcher.ChannelState state = dispatcher.state();
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("configured", channels.configured(Channel.EMAIL));
        details.put("paused", state.paused());
        details.put("pausedUntil", state.pausedUntil());
        if (state.paused()) {
            reasons.add("EMAIL_CONFIG");
        }
        try {
            long failed = notifications.countFailedSince(FAILED_WINDOW, false);
            details.put("failedLastHour", failed);
            if (failed > 0) {
                reasons.add("NOTIFICATIONS_FAILED");
            }
        } catch (RuntimeException e) {
            log.debug("Feed health: notifications could not be read ({})", e.getClass().getName());
            details.put("failedLastHour", null);
        }
        return details;
    }

    /** Polled, but no success for more than 3 × the effective interval (since the last success or creation). */
    private boolean isStale(FeedSource source, Instant now) {
        if (source.lastPolledAt() == null) {
            return false;                                   // never polled yet: nothing to judge
        }
        Duration interval = Duration.ofSeconds(
                properties.intervals().effectiveSeconds(source.kind(), source.pollIntervalSeconds()));
        Instant since = source.lastSuccessAt() != null ? source.lastSuccessAt() : source.createdAt();
        return since != null && since.plus(interval.multipliedBy(STALE_INTERVALS)).isBefore(now);
    }

    private static List<String> ids(List<UUID> ids) {
        return ids.stream().limit(MAX_LISTED_IDS).map(UUID::toString).toList();
    }
}
