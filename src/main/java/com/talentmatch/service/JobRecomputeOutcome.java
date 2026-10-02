package com.talentmatch.service;

/** Result of recomputing one job's matches. */
public sealed interface JobRecomputeOutcome
        permits JobRecomputeOutcome.Recomputed, JobRecomputeOutcome.SkippedNoSkills,
        JobRecomputeOutcome.SkippedJobDeleted {

    /** Scores were (re)written for {@code candidates} candidates (0 if nothing was stale). */
    record Recomputed(int candidates) implements JobRecomputeOutcome {
    }

    /** The job lists no skills, so it is not matchable. */
    record SkippedNoSkills() implements JobRecomputeOutcome {
    }

    /** The job was deleted after the run took its snapshot. */
    record SkippedJobDeleted() implements JobRecomputeOutcome {
    }
}
