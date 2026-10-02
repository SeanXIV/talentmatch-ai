package com.talentmatch.service;

import java.util.List;
import java.util.UUID;

/**
 * Response of GET /api/jobs/{id}/matches.
 *
 * @param matchable             false when the job has no skills
 * @param reason                JOB_HAS_NO_SKILLS / NO_CANDIDATES, or null
 * @param message               user-facing explanation of reason, or null
 * @param recomputedCandidates  scores refreshed by this request
 */
public record MatchPageView(
        UUID jobId,
        String jobTitle,
        String company,
        boolean matchable,
        MatchReason reason,
        String message,
        int page,
        int limit,
        int totalPages,
        long totalElements,
        int recomputedCandidates,
        List<MatchView> matches) {
}
