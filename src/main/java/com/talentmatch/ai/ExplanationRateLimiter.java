package com.talentmatch.ai;

import com.talentmatch.service.exception.RegenerateRateLimitedException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * Allows {@code regenerate=true} at most once per job per {@code regenerateWindow} (per instance).
 * A request that fails before producing a response releases its lease, so it does not consume
 * the slot.
 */
@Component
public class ExplanationRateLimiter {

    static final int EVICTION_THRESHOLD = 10_000;
    private static final long NANOS_PER_SECOND = 1_000_000_000L;

    /** Proof of a granted regenerate slot; pass it to {@link #release(Lease)} on failure. */
    public record Lease(UUID jobId, Instant grantedAt) {
    }

    private final Map<UUID, Instant> lastGranted = new ConcurrentHashMap<>();
    private final Duration window;
    private final Clock clock;

    public ExplanationRateLimiter(AiProperties properties, Clock clock) {
        this.window = properties.regenerateWindow();
        this.clock = clock;
    }

    /**
     * Grants the job's regenerate slot.
     *
     * @throws RegenerateRateLimitedException if the job was regenerated within the window
     */
    public Lease acquire(UUID jobId) {
        Instant now = clock.instant();
        if (lastGranted.size() > EVICTION_THRESHOLD) {
            lastGranted.entrySet().removeIf(e -> !now.isBefore(e.getValue().plus(window)));
        }
        Instant[] granted = new Instant[1];
        Duration[] remaining = new Duration[1];
        lastGranted.compute(jobId, (id, last) -> {
            if (last == null || !now.isBefore(last.plus(window))) {
                granted[0] = now;
                return now;
            }
            remaining[0] = Duration.between(now, last.plus(window));
            return last;
        });
        if (granted[0] == null) {
            long nanos = remaining[0].toNanos();
            long seconds = Math.max(1L, (nanos + NANOS_PER_SECOND - 1) / NANOS_PER_SECOND);
            throw new RegenerateRateLimitedException((int) Math.min(Integer.MAX_VALUE, seconds));
        }
        return new Lease(jobId, granted[0]);
    }

    /** Frees the slot if it is still held by this lease. */
    public void release(Lease lease) {
        if (lease != null) {
            lastGranted.remove(lease.jobId(), lease.grantedAt());
        }
    }
}
