package com.talentmatch.notify;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * The dispatcher's meters (§6.6), here so {@code notify} never imports {@code feed}. Tags are lower case:
 * <ul>
 *   <li>{@value #NOTIFICATIONS} (counter): {@code channel} (email) and {@code outcome}: sent, transient,
 *       permanent, config ({@link SmtpFailure#outcome()}), expired, no_content, internal_error. The test
 *       email is not counted;</li>
 *   <li>{@value #DETECTION_LATENCY} (timer, no tags): sent time minus the posting date, or the time the
 *       feed first saw the job when the provider gave no date.</li>
 * </ul>
 * Recording never throws: a meter problem is logged at debug by class name.
 */
@Component
public class NotifyMetrics {

    private static final Logger log = LoggerFactory.getLogger(NotifyMetrics.class);

    public static final String NOTIFICATIONS = "talentmatch.feed.notifications";
    public static final String DETECTION_LATENCY = "talentmatch.feed.detection.latency";

    private final MeterRegistry registry;

    @Autowired
    public NotifyMetrics(ObjectProvider<MeterRegistry> registry) {
        this(registry.getIfAvailable());
    }

    /** {@code null} records into {@link Metrics#globalRegistry}. */
    public NotifyMetrics(MeterRegistry registry) {
        this.registry = registry == null ? Metrics.globalRegistry : registry;
    }

    /** Records into the global registry (callers built without Spring). */
    public static NotifyMetrics global() {
        return new NotifyMetrics((MeterRegistry) null);
    }

    public void notification(Channel channel, String outcome, int count) {
        if (count <= 0) {
            return;
        }
        try {
            Counter.builder(NOTIFICATIONS).description("Feed notifications by outcome")
                    .tag("channel", channel == null ? "unknown" : channel.name().toLowerCase(Locale.ROOT))
                    .tag("outcome", outcome == null ? "unknown" : outcome.toLowerCase(Locale.ROOT))
                    .register(registry).increment(count);
        } catch (RuntimeException e) {
            log.debug("Could not record notification metrics ({})", e.getClass().getSimpleName());
        }
    }

    /** Nothing is recorded without a send time or a base time; a negative span counts as zero. */
    public void detectionLatency(Instant sentAt, Instant postedAt, Instant firstSeenAt) {
        Instant base = postedAt != null ? postedAt : firstSeenAt;
        if (sentAt == null || base == null) {
            return;
        }
        Duration latency = Duration.between(base, sentAt);
        try {
            Timer.builder(DETECTION_LATENCY).description("Time from posting to notification sent")
                    .register(registry).record(latency.isNegative() ? Duration.ZERO : latency);
        } catch (RuntimeException e) {
            log.debug("Could not record detection latency ({})", e.getClass().getSimpleName());
        }
    }
}
