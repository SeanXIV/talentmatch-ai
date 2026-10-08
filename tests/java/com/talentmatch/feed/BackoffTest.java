package com.talentmatch.feed;

import static org.assertj.core.api.Assertions.assertThat;

import com.talentmatch.feed.source.SourceFailure;
import com.talentmatch.feed.source.SourceFailure.Kind;
import java.time.Duration;
import java.time.Instant;
import java.util.SplittableRandom;
import java.util.random.RandomGenerator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** §4.3 / §6.1 next poll times. */
class BackoffTest {

    private static final Instant NOW = Instant.parse("2026-10-08T12:00:00Z");
    private static final Duration INTERVAL = Duration.ofMinutes(5);
    private static final Duration MAX = Duration.ofHours(1);
    private static final Duration NOT_FOUND = Duration.ofHours(6);

    /** Always returns the lower bound: shows the minimum of a range. */
    private static final RandomGenerator LOW = new RandomGenerator() {
        @Override
        public long nextLong() {
            return 0;
        }

        @Override
        public long nextLong(long origin, long bound) {
            return origin;
        }
    };

    /** Always returns the upper (inclusive) end. */
    private static final RandomGenerator HIGH = new RandomGenerator() {
        @Override
        public long nextLong() {
            return 0;
        }

        @Override
        public long nextLong(long origin, long bound) {
            return bound - 1;
        }
    };

    private static Duration after(SourceFailure f, int failures, Duration interval, RandomGenerator r) {
        return Duration.between(NOW, Backoff.afterFailure(f, failures, interval, MAX, NOT_FOUND, NOW, r));
    }

    private static void assertWithin10(Duration actual, Duration base) {
        long ms = base.toMillis();
        assertThat(actual.toMillis()).isBetween(ms - ms / 10, ms + ms / 10);
    }

    @Test
    void successIsIntervalWithJitter() {
        assertThat(Duration.between(NOW, Backoff.afterSuccess(NOW, INTERVAL, LOW))).isEqualTo(Duration.ofSeconds(270));
        assertThat(Duration.between(NOW, Backoff.afterSuccess(NOW, INTERVAL, HIGH))).isEqualTo(Duration.ofSeconds(330));
    }

    @ParameterizedTest
    @EnumSource(value = Kind.class, names = {"SERVER_ERROR", "NETWORK", "TIMEOUT", "INVALID_RESPONSE", "TOO_LARGE"})
    void transientKindsBackOffExponentially(Kind kind) {
        SplittableRandom r = new SplittableRandom(1);
        assertWithin10(after(SourceFailure.of(kind), 1, INTERVAL, r), Duration.ofMinutes(10));
        assertWithin10(after(SourceFailure.of(kind), 2, INTERVAL, r), Duration.ofMinutes(20));
        assertWithin10(after(SourceFailure.of(kind), 3, INTERVAL, r), Duration.ofMinutes(40));
        assertWithin10(after(SourceFailure.of(kind), 4, INTERVAL, r), MAX);
        assertWithin10(after(SourceFailure.of(kind), 50, INTERVAL, r), MAX);
    }

    @ParameterizedTest
    @EnumSource(value = Kind.class, names = {"NOT_FOUND", "UNAUTHORIZED"})
    void notFoundAndUnauthorizedWaitTheNotFoundBackoff(Kind kind) {
        assertWithin10(after(SourceFailure.of(kind), 1, INTERVAL, new SplittableRandom(2)), NOT_FOUND);
        assertWithin10(after(SourceFailure.of(kind), 9, INTERVAL, new SplittableRandom(3)), NOT_FOUND);
    }

    @Test
    void rateLimitedWithoutRetryAfterIsExponential() {
        assertWithin10(after(SourceFailure.of(Kind.RATE_LIMITED, 429), 2, INTERVAL, new SplittableRandom(4)),
                Duration.ofMinutes(20));
    }

    @Test
    void exponentialCapAndIntervalFloor() {
        assertThat(Backoff.exponential(INTERVAL, 0, MAX)).isEqualTo(INTERVAL);
        assertThat(Backoff.exponential(INTERVAL, 1, MAX)).isEqualTo(Duration.ofMinutes(10));
        assertThat(Backoff.exponential(INTERVAL, 30, MAX)).isEqualTo(MAX);
        assertThat(Backoff.exponential(INTERVAL, Integer.MAX_VALUE, MAX)).isEqualTo(MAX);
        assertThat(Backoff.exponential(INTERVAL, -3, MAX)).isEqualTo(INTERVAL);
        // interval longer than the cap: never polled more often than the interval
        Duration day = Duration.ofHours(24);
        assertThat(Backoff.exponential(day, 3, MAX)).isEqualTo(day);
        assertWithin10(after(SourceFailure.of(Kind.SERVER_ERROR), 3, day, new SplittableRandom(5)), day);
    }

    @Test
    void retryAfterIsClampedAndOnlyDelayedByJitter() {
        SourceFailure in120 = new SourceFailure(Kind.RATE_LIMITED, 429, Duration.ofSeconds(120));
        // inside [interval, max]: 120s with interval 120s
        Duration twoMin = Duration.ofSeconds(120);
        assertThat(after(in120, 1, twoMin, LOW)).isEqualTo(twoMin);
        assertThat(after(in120, 1, twoMin, HIGH)).isEqualTo(Duration.ofSeconds(132));
        // below the interval: raised to the interval
        assertThat(after(in120, 1, INTERVAL, LOW)).isEqualTo(INTERVAL);
        // above the cap: lowered to max-backoff
        SourceFailure inDay = new SourceFailure(Kind.RATE_LIMITED, 429, Duration.ofDays(1));
        assertThat(after(inDay, 1, INTERVAL, LOW)).isEqualTo(MAX);
        // interval above max-backoff: the cap is the interval
        Duration twoHours = Duration.ofHours(2);
        assertThat(after(inDay, 1, twoHours, LOW)).isEqualTo(twoHours);
        // jitter is never negative
        SourceFailure in600 = new SourceFailure(Kind.RATE_LIMITED, 429, Duration.ofSeconds(600));
        RandomGenerator r = new SplittableRandom(6);
        for (int i = 0; i < 500; i++) {
            assertThat(after(in600, 1, INTERVAL, r).toMillis()).isBetween(600_000L, 660_000L);
        }
    }

    @Test
    void jitterStaysWithinTenPercentAndUsesBothSides() {
        RandomGenerator r = new SplittableRandom(42);
        long min = Long.MAX_VALUE;
        long max = Long.MIN_VALUE;
        for (int i = 0; i < 2000; i++) {
            long ms = Backoff.jitter(Duration.ofSeconds(100), r).toMillis();
            assertThat(ms).isBetween(90_000L, 110_000L);
            min = Math.min(min, ms);
            max = Math.max(max, ms);
        }
        assertThat(min).isLessThan(95_000L);
        assertThat(max).isGreaterThan(105_000L);
        assertThat(Backoff.jitter(Duration.ZERO, r)).isEqualTo(Duration.ZERO);
        assertThat(Backoff.positiveJitter(Duration.ofMillis(5), r)).isEqualTo(Duration.ZERO);
    }

    @Test
    void nextUtcMidnightPlusUpToFiveMinutes() {
        assertThat(Backoff.nextUtcMidnight(NOW, LOW)).isEqualTo(Instant.parse("2026-10-09T00:00:00Z"));
        assertThat(Backoff.nextUtcMidnight(NOW, HIGH)).isEqualTo(Instant.parse("2026-10-09T00:05:00Z"));
        Instant justBefore = Instant.parse("2026-10-08T23:59:59.999Z");
        assertThat(Backoff.nextUtcMidnight(justBefore, LOW)).isEqualTo(Instant.parse("2026-10-09T00:00:00Z"));
        Instant atMidnight = Instant.parse("2026-10-09T00:00:00Z");
        assertThat(Backoff.nextUtcMidnight(atMidnight, LOW)).isEqualTo(Instant.parse("2026-10-10T00:00:00Z"));
        RandomGenerator r = new SplittableRandom(7);
        for (int i = 0; i < 200; i++) {
            Instant next = Backoff.nextUtcMidnight(justBefore, r);
            assertThat(next).isBetween(Instant.parse("2026-10-09T00:00:00Z"), Instant.parse("2026-10-09T00:05:00Z"));
        }
    }
}
