package com.talentmatch.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Mutable, thread-safe progress of one batch recompute; read via {@link #view()}. */
public final class RecomputeRun {

    /** Cap on skippedJobIds listed in the view. */
    public static final int MAX_SKIPPED_REPORTED = 50;

    private final UUID id;
    private final boolean onlyStale;
    private final Instant startedAt;
    private final int maxFailuresReported;

    private RecomputeState state = RecomputeState.QUEUED;
    private int totalJobs;
    private int processed;
    private int skipped;
    private int failed;
    private long matchesWritten;
    private Instant finishedAt;
    private String failureReason;
    private final List<UUID> skippedJobIds = new ArrayList<>();
    private final List<RecomputeRunView.Failure> failures = new ArrayList<>();

    public RecomputeRun(UUID id, boolean onlyStale, Instant startedAt, int maxFailuresReported) {
        this.id = id;
        this.onlyStale = onlyStale;
        this.startedAt = startedAt;
        this.maxFailuresReported = maxFailuresReported;
    }

    public UUID id() {
        return id;
    }

    public boolean onlyStale() {
        return onlyStale;
    }

    public Instant startedAt() {
        return startedAt;
    }

    public synchronized void begin(int totalJobs) {
        this.totalJobs = totalJobs;
        this.state = RecomputeState.RUNNING;
    }

    public synchronized void recordRecomputed(int matches) {
        processed++;
        matchesWritten += matches;
    }

    public synchronized void recordSkipped(UUID jobId) {
        skipped++;
        if (skippedJobIds.size() < MAX_SKIPPED_REPORTED) {
            skippedJobIds.add(jobId);
        }
    }

    public synchronized void recordFailure(UUID jobId, String message) {
        failed++;
        if (failures.size() < maxFailuresReported) {
            failures.add(new RecomputeRunView.Failure(jobId, message));
        }
    }

    /** Marks the run finished: SUCCEEDED if no job failed, else COMPLETED_WITH_ERRORS. */
    public synchronized void complete(Instant now) {
        state = failed == 0 ? RecomputeState.SUCCEEDED : RecomputeState.COMPLETED_WITH_ERRORS;
        finishedAt = now;
    }

    /** Marks the run FAILED (the loop itself broke). */
    public synchronized void fail(String reason, Instant now) {
        state = RecomputeState.FAILED;
        failureReason = reason;
        finishedAt = now;
    }

    public synchronized RecomputeState state() {
        return state;
    }

    public synchronized RecomputeRunView view() {
        int done = processed + skipped + failed;
        int percent;
        if (totalJobs == 0) {
            percent = state.isFinished() ? 100 : 0;
        } else {
            percent = (int) Math.min(100, (long) done * 100 / totalJobs);
        }
        return new RecomputeRunView(id, state, onlyStale, totalJobs, processed, skipped, failed, percent,
                matchesWritten, startedAt, finishedAt, message(done), List.copyOf(skippedJobIds),
                List.copyOf(failures));
    }

    private String message(int done) {
        return switch (state) {
            case QUEUED -> "Recompute queued; it will start in a moment.";
            case RUNNING -> "Recomputing matches: " + done + " of " + totalJobs + " jobs done.";
            case SUCCEEDED -> "Recompute finished: " + processed + " job(s) recomputed, " + skipped
                    + " skipped, " + matchesWritten + " match(es) written.";
            case COMPLETED_WITH_ERRORS -> "Recompute finished with errors: " + failed + " of " + totalJobs
                    + " job(s) failed (see failures); " + processed + " recomputed, " + skipped + " skipped.";
            case FAILED -> "Recompute failed after " + done + " of " + totalJobs + " jobs: "
                    + (failureReason == null ? "unexpected error." : failureReason);
        };
    }
}
