package com.talentmatch.notify;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** §4.9: min(initial × 2^(attempts−1), max) ± 10%; 1, 2, 4, 8 min, then the 15m cap (D1). */
class NotificationBackoffTest {

    private static final Duration INITIAL = Duration.ofMinutes(1);
    private static final Duration MAX = Duration.ofMinutes(15);

    @Test
    void sequenceWithoutJitter() {
        assertThat(NotificationBackoff.delay(1, INITIAL, MAX, 0)).isEqualTo(Duration.ofMinutes(1));
        assertThat(NotificationBackoff.delay(2, INITIAL, MAX, 0)).isEqualTo(Duration.ofMinutes(2));
        assertThat(NotificationBackoff.delay(3, INITIAL, MAX, 0)).isEqualTo(Duration.ofMinutes(4));
        assertThat(NotificationBackoff.delay(4, INITIAL, MAX, 0)).isEqualTo(Duration.ofMinutes(8));
        for (int a = 5; a <= 10; a++) {
            assertThat(NotificationBackoff.delay(a, INITIAL, MAX, 0)).as("attempt %d", a).isEqualTo(MAX);
        }
    }

    @Test
    void jitterStaysWithinTenPercentAndIsClamped() {
        assertThat(NotificationBackoff.delay(1, INITIAL, MAX, 0.1)).isEqualTo(Duration.ofSeconds(66));
        assertThat(NotificationBackoff.delay(1, INITIAL, MAX, -0.1)).isEqualTo(Duration.ofSeconds(54));
        assertThat(NotificationBackoff.delay(1, INITIAL, MAX, 5.0)).isEqualTo(Duration.ofSeconds(66));
        assertThat(NotificationBackoff.delay(1, INITIAL, MAX, -5.0)).isEqualTo(Duration.ofSeconds(54));
        assertThat(NotificationBackoff.delay(1, INITIAL, MAX, Double.NaN)).isEqualTo(INITIAL);
        assertThat(NotificationBackoff.delay(10, INITIAL, MAX, 0.1)).isEqualTo(Duration.ofSeconds(990));
    }

    @Test
    void overflowGuard() {
        for (int a : new int[]{30, 60, 61, 62, 63, 64, 1000, Integer.MAX_VALUE}) {
            assertThat(NotificationBackoff.delay(a, INITIAL, MAX, 0)).as("attempt %d", a).isEqualTo(MAX);
        }
        assertThat(NotificationBackoff.delay(0, INITIAL, MAX, 0)).isEqualTo(INITIAL);
        assertThat(NotificationBackoff.delay(-3, INITIAL, MAX, 0)).isEqualTo(INITIAL);
    }

    @Test
    void nextAddsTheDelay() {
        Instant now = Instant.parse("2026-10-11T10:00:00Z");
        assertThat(NotificationBackoff.next(now, 3, INITIAL, MAX, 0)).isEqualTo(now.plus(Duration.ofMinutes(4)));
    }
}
