package com.talentmatch.notify;

import java.time.Duration;
import java.time.Instant;

/**
 * Retry delay after a transient delivery failure (§4.9). Pure:
 * {@code now + min(initial × 2^(attempts−1), max) × (1 + jitter)}. With the defaults (1m, 15m) and
 * max-attempts 5 the delays are 1, 2, 4 and 8 minutes, and the 5th failure is final (D1); the 15m cap
 * applies to larger max-attempts.
 */
public final class NotificationBackoff {

    /** Jitter bounds: ±10%. */
    public static final double MAX_JITTER = 0.1;

    private NotificationBackoff() {
    }

    /**
     * @param attemptsAfter the attempts counted so far, including the one that just failed (≥ 1)
     * @param jitter        a random factor in [−0.1, 0.1] (clamped)
     */
    public static Instant next(Instant now, int attemptsAfter, Duration initial, Duration max, double jitter) {
        return now.plus(delay(attemptsAfter, initial, max, jitter));
    }

    public static Duration delay(int attemptsAfter, Duration initial, Duration max, double jitter) {
        int exponent = Math.max(0, attemptsAfter - 1);
        long initialMs = Math.max(1, initial.toMillis());
        long maxMs = Math.max(initialMs, max.toMillis());
        long base;
        if (exponent >= 62 || initialMs > (maxMs >> Math.min(exponent, 62))) {
            base = maxMs;                                       // overflow guard: already past the cap
        } else {
            base = Math.min(initialMs << exponent, maxMs);
        }
        double j = Double.isNaN(jitter) ? 0.0 : Math.max(-MAX_JITTER, Math.min(MAX_JITTER, jitter));
        return Duration.ofMillis(Math.max(1, Math.round(base * (1.0 + j))));
    }
}
