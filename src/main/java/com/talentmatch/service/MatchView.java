package com.talentmatch.service;

import com.talentmatch.ai.ExplanationStatus;
import com.talentmatch.ai.ExplanationView;
import java.time.Instant;
import java.util.UUID;

/**
 * One ranked candidate for a job.
 *
 * @param rank              1-based rank across pages (page * limit + index + 1)
 * @param score             cached score in [0, 1], rounded to 4 decimals
 * @param scorePercent      score as a whole percentage
 * @param aiExplanation     current AI explanation text; non-null only when READY
 * @param explanationStatus READY, PENDING, STALE or UNAVAILABLE
 * @param explanation       always present: the AI explanation, or a template built from the breakdown
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
        ExplanationView explanation,
        Instant computedAt) {
}
