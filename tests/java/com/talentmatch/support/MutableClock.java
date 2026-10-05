package com.talentmatch.support;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Test clock: system time plus a movable offset (ticking), or a fixed instant that only moves when
 * advanced. Thread-safe.
 */
public final class MutableClock extends Clock {

    private final AtomicReference<Duration> offset = new AtomicReference<>(Duration.ZERO);
    private final AtomicReference<Instant> fixed;

    private MutableClock(Instant fixed) {
        this.fixed = new AtomicReference<>(fixed);
    }

    /** Ticks with the system clock; {@link #advance(Duration)} adds an offset. */
    public static MutableClock ticking() {
        return new MutableClock(null);
    }

    /** Stands still at {@code start} until advanced. */
    public static MutableClock fixedAt(Instant start) {
        return new MutableClock(start);
    }

    public void advance(Duration d) {
        if (fixed.get() != null) {
            fixed.updateAndGet(i -> i.plus(d));
        } else {
            offset.updateAndGet(o -> o.plus(d));
        }
    }

    @Override
    public Instant instant() {
        Instant f = fixed.get();
        return f != null ? f : Instant.now().plus(offset.get());
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }
}
