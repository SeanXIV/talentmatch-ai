package com.talentmatch.ai;

import java.util.Objects;

/**
 * One (job, candidate) pair to explain. Requests in one call may span many jobs (candidate side) or
 * many candidates (job side).
 *
 * @param job the job side of the prompt
 * @param row the ranked match; {@code row.rank()} decides top-N eligibility
 */
public record ExplanationRequest(JobContext job, MatchContext row) {

    public ExplanationRequest {
        Objects.requireNonNull(job, "job");
        Objects.requireNonNull(row, "row");
    }
}
