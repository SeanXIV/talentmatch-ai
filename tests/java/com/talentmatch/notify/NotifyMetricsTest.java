package com.talentmatch.notify;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Step 11: {@link NotifyMetrics} counter tags and the detection-latency base and clamping. */
class NotifyMetricsTest {

    private static final Instant SENT = Instant.parse("2026-10-13T12:00:00Z");

    private SimpleMeterRegistry registry;
    private NotifyMetrics metrics;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        metrics = new NotifyMetrics(registry);
    }

    private Timer latency() {
        return registry.find(NotifyMetrics.DETECTION_LATENCY).timer();
    }

    @Test
    void latencyUsesPostedAtWhenPresent() {
        metrics.detectionLatency(SENT, SENT.minusSeconds(300), SENT.minusSeconds(60));
        assertThat(latency().count()).isOne();
        assertThat(latency().totalTime(TimeUnit.SECONDS)).isEqualTo(300);
        assertThat(latency().getId().getTags()).as("no tags").isEmpty();
    }

    @Test
    void latencyFallsBackToFirstSeenAt() {
        metrics.detectionLatency(SENT, null, SENT.minusSeconds(90));
        assertThat(latency().totalTime(TimeUnit.SECONDS)).isEqualTo(90);
    }

    @Test
    void aPostedAtAfterTheSendCountsAsZero() {
        metrics.detectionLatency(SENT, SENT.plus(Duration.ofHours(2)), SENT.minusSeconds(60));
        assertThat(latency().count()).isOne();
        assertThat(latency().totalTime(TimeUnit.NANOSECONDS)).isZero();
    }

    @Test
    void nothingWithoutABaseOrASendTime() {
        metrics.detectionLatency(SENT, null, null);
        metrics.detectionLatency(null, SENT, SENT);
        assertThat(registry.find(NotifyMetrics.DETECTION_LATENCY).meters()).isEmpty();
    }

    @Test
    void notificationsAreCountedByChannelAndLowerCasedOutcome() {
        metrics.notification(Channel.EMAIL, "sent", 1);
        metrics.notification(Channel.EMAIL, "EXPIRED", 4);
        metrics.notification(Channel.EMAIL, "expired", 0);
        assertThat(registry.find(NotifyMetrics.NOTIFICATIONS).tags("channel", "email", "outcome", "sent").counter()
                .count()).isEqualTo(1);
        assertThat(registry.find(NotifyMetrics.NOTIFICATIONS).tags("channel", "email", "outcome", "expired")
                .counter().count()).isEqualTo(4);
    }

    @Test
    void aBrokenRegistryNeverThrows() {
        MeterRegistry broken = mock(MeterRegistry.class);
        when(broken.config()).thenThrow(new IllegalStateException("broken"));
        NotifyMetrics m = new NotifyMetrics(broken);
        assertThatCode(() -> {
            m.notification(Channel.EMAIL, "sent", 1);
            m.notification(null, null, 1);
            m.detectionLatency(SENT, SENT.minusSeconds(1), null);
        }).doesNotThrowAnyException();
    }
}
