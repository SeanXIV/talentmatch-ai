package com.talentmatch.service;

/** Lifecycle of a batch recompute run. */
public enum RecomputeState {
    QUEUED,
    RUNNING,
    SUCCEEDED,
    COMPLETED_WITH_ERRORS,
    FAILED;

    public boolean isFinished() {
        return this == SUCCEEDED || this == COMPLETED_WITH_ERRORS || this == FAILED;
    }
}
