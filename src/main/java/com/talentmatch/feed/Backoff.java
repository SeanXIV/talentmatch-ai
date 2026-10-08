package com.talentmatch.feed;

import com.talentmatch.feed.source.SourceFailure;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.random.RandomGenerator;

/**
 * When a source is polled next (§4.3, §6.1; pure). Every result gets jitter so polls don't line up.
 *
 * <table>
 *   <caption>Next poll</caption>
 *   <tr><td>success / not modified</td><td>{@code now + interval} ± 10%</td></tr>
 *   <tr><td>RATE_LIMITED with Retry-After</td><td>{@code now + clamp(Retry-After, interval, max-backoff)}
 *       plus 0..10% (never earlier than the provider asked)</td></tr>
 *   <tr><td>SERVER_ERROR, NETWORK, TIMEOUT, INVALID_RESPONSE, TOO_LARGE, RATE_LIMITED without
 *       Retry-After</td><td>{@code now + max(interval, min(interval × 2^failures, max-backoff))} ± 10%</td></tr>
 *   <tr><td>NOT_FOUND, UNAUTHORIZED</td><td>{@code now + not-found-backoff} ± 10%</td></tr>
 *   <tr><td>daily budget exhausted</td><td>the next UTC midnight plus 0..5 minutes</td></tr>
 * </table>
 */
public final class Backoff {

    /** ±10% jitter. */
    static final int JITTER_PERCENT = 10;
    /** 2^20 × 1 minute is far beyond any cap; keeps the multiplication from overflowing. */
    private static final int MAX_EXPONENT = 20;
    private static final long MIDNIGHT_JITTER_SECONDS = 300;

    private Backoff() {
    }

    /** After OK or NOT_MODIFIED. */
    public static Instant afterSuccess(Instant now, Duration interval, RandomGenerator random) {
        return now.plus(jitter(interval, random));
    }

    /**
     * After a failed poll.
     *
     * @param consecutiveFailures the failure count including this one (≥ 1)
     * @param interval            the source's effective interval
     */
    public static Instant afterFailure(SourceFailure failure, int consecutiveFailures, Duration interval,
                                       Duration maxBackoff, Duration notFoundBackoff, Instant now,
                                       RandomGenerator random) {
        Objects.requireNonNull(failure, "failure");
        switch (failure.kind()) {
            case NOT_FOUND, UNAUTHORIZED -> {
                return now.plus(jitter(notFoundBackoff, random));
            }
            case RATE_LIMITED -> {
                if (failure.retryAfter() != null) {
                    Duration cap = maxBackoff.compareTo(interval) < 0 ? interval : maxBackoff;
                    Duration wait = clamp(failure.retryAfter(), interval, cap);
                    return now.plus(wait).plus(positiveJitter(wait, random));
                }
                return now.plus(jitter(exponential(interval, consecutiveFailures, maxBackoff), random));
            }
            default -> {
                return now.plus(jitter(exponential(interval, consecutiveFailures, maxBackoff), random));
            }
        }
    }

    /** {@code max(interval, min(interval × 2^failures, max))}: a failing source is never polled more often. */
    public static Duration exponential(Duration interval, int failures, Duration max) {
        int exponent = Math.max(0, Math.min(failures, MAX_EXPONENT));
        long factor = 1L << exponent;
        Duration grown = interval.getSeconds() > max.getSeconds() / factor ? max : interval.multipliedBy(factor);
        Duration capped = grown.compareTo(max) > 0 ? max : grown;
        return capped.compareTo(interval) < 0 ? interval : capped;
    }

    /** The next UTC midnight after {@code now}, plus 0..5 minutes. */
    public static Instant nextUtcMidnight(Instant now, RandomGenerator random) {
        Instant midnight = now.atOffset(ZoneOffset.UTC).toLocalDate().plusDays(1)
                .atStartOfDay(ZoneOffset.UTC).toInstant();
        return midnight.plusSeconds(random.nextLong(0, MIDNIGHT_JITTER_SECONDS + 1));
    }

    /** {@code base} ± 10% (millisecond resolution, never negative). */
    static Duration jitter(Duration base, RandomGenerator random) {
        long ms = base.toMillis();
        long spread = ms * JITTER_PERCENT / 100;
        long delta = spread <= 0 ? 0 : random.nextLong(-spread, spread + 1);
        return Duration.ofMillis(Math.max(0, ms + delta));
    }

    /** 0..10% of {@code base}. */
    static Duration positiveJitter(Duration base, RandomGenerator random) {
        long spread = base.toMillis() * JITTER_PERCENT / 100;
        return Duration.ofMillis(spread <= 0 ? 0 : random.nextLong(0, spread + 1));
    }

    private static Duration clamp(Duration value, Duration min, Duration max) {
        if (value.compareTo(min) < 0) {
            return min;
        }
        return value.compareTo(max) > 0 ? max : value;
    }
}
