package com.talentmatch.service;

import java.time.Instant;
import java.util.UUID;

/**
 * One ranked candidate for a job.
 *
 * @param rank              1-based rank across pages (page * limit + index + 1)
 * @param score             cached score in [0, 1], rounded to 4 decimals
 * @param scorePercent      score as a whole percentage
 * @param aiExplanation     always null in Phase 2
 * @param explanationStatus always UNAVAILABLE in Phase 2
 * @param computedAt        when the score was last verified fresh
 */
public record MatchView(
        int rank,
        UUID candidateId,
        String candidateName,
        double score,
        int scorePercent,
        String summary,
        MatchBreakdownView breakdown,
        String aiExplanation,
        ExplanationStatus explanationStatus,
        Instant computedAt) {
}
