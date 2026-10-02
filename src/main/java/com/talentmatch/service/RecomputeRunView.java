package com.talentmatch.service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Immutable snapshot of a recompute run (body of POST/GET /api/matches/recompute...).
 * {@code processed + skipped + failed} is the number of jobs done so far.
 */
public record RecomputeRunView(
        UUID runId,
        RecomputeState state,
        boolean onlyStale,
        int totalJobs,
        int processed,
        int skipped,
        int failed,
        int percentComplete,
        long matchesWritten,
        Instant startedAt,
        Instant finishedAt,
        String message,
        List<UUID> skippedJobIds,
        List<Failure> failures) {

    /** A job that could not be recomputed, with a user-safe reason. */
    public record Failure(UUID jobId, String message) {
    }
}
