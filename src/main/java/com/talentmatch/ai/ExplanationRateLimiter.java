package com.talentmatch.ai;

import com.talentmatch.service.exception.RegenerateRateLimitedException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * Allows {@code regenerate=true} at most once per job (job matches) and once per candidate
 * (candidate matches) per {@code regenerateWindow} (per instance). The two scopes never collide,
 * even for the same UUID. A request that fails before producing a response releases its lease, so
 * it does not consume the slot.
 */
@Component
public class ExplanationRateLimiter {

    static final int EVICTION_THRESHOLD = 10_000;
    private static final long NANOS_PER_SECOND = 1_000_000_000L;

    /** What a regenerate slot belongs to. */
    public enum Scope {
        /** {@code GET /api/jobs/{id}/matches}. */
        JOB,
        /** {@code GET /api/candidates/{id}/matches} and {@code GET /api/profile/matches}. */
        CANDIDATE
    }

    /** Proof of a granted regenerate slot; pass it to {@link #release(Lease)} on failure. */
    public record Lease(Scope scope, UUID id, Instant grantedAt) {

        public Lease {
            Objects.requireNonNull(scope, "scope");
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(grantedAt, "grantedAt");
        }

        /** The job id for a JOB lease, else null. */
        public UUID jobId() {
            return scope == Scope.JOB ? id : null;
        }

        /** The candidate id for a CANDIDATE lease, else null. */
        public UUID candidateId() {
            return scope == Scope.CANDIDATE ? id : null;
        }
    }

    private record Key(Scope scope, UUID id) {
    }

    private final Map<Key, Instant> lastGranted = new ConcurrentHashMap<>();
    private final Duration window;
    private final Clock clock;

    public ExplanationRateLimiter(AiProperties properties, Clock clock) {
        this.window = properties.regenerateWindow();
        this.clock = clock;
    }

    /**
     * Grants the job's regenerate slot.
     *
     * @throws RegenerateRateLimitedException (job message) if the job was regenerated within the window
     */
    public Lease acquire(UUID jobId) {
        return acquire(Scope.JOB, jobId);
    }

    /**
     * Grants the candidate's regenerate slot (candidate matches).
     *
     * @throws RegenerateRateLimitedException (candidate message) if the candidate's matches were
     *         regenerated within the window
     */
    public Lease acquireForCandidate(UUID candidateId) {
        return acquire(Scope.CANDIDATE, candidateId);
    }

    private Lease acquire(Scope scope, UUID id) {
        Objects.requireNonNull(id, "id");
        Instant now = clock.instant();
        if (lastGranted.size() > EVICTION_THRESHOLD) {
            lastGranted.entrySet().removeIf(e -> !now.isBefore(e.getValue().plus(window)));
        }
        Instant[] granted = new Instant[1];
        Duration[] remaining = new Duration[1];
        lastGranted.compute(new Key(scope, id), (k, last) -> {
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
            int retryAfter = (int) Math.min(Integer.MAX_VALUE, seconds);
            throw switch (scope) {
                case JOB -> RegenerateRateLimitedException.forJob(retryAfter);
                case CANDIDATE -> RegenerateRateLimitedException.forCandidate(retryAfter);
            };
        }
        return new Lease(scope, id, granted[0]);
    }

    /** Frees the slot if it is still held by this lease. */
    public void release(Lease lease) {
        if (lease != null) {
            lastGranted.remove(new Key(lease.scope(), lease.id()), lease.grantedAt());
        }
    }
}
