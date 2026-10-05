package com.talentmatch.ai;

import com.talentmatch.domain.scoring.MatchEvaluation;
import com.talentmatch.repository.MatchJdbcRepository;
import java.time.Instant;
import java.util.UUID;

/**
 * One ranked match as input to the explanation layer.
 *
 * @param rank               1-based rank across pages
 * @param candidateUpdatedAt candidate.updated_at as read with the page (guards the explanation write)
 * @param storedScore        raw job_match.score as read (guards the explanation write)
 * @param scorePercent       score as a whole percentage
 * @param evaluation         deterministic breakdown for this pair
 * @param stored             persisted explanation, or null when there is none
 */
public record MatchContext(
        int rank,
        UUID candidateId,
        String candidateName,
        String candidateSummary,
        Instant candidateUpdatedAt,
        double storedScore,
        int scorePercent,
        MatchEvaluation evaluation,
        MatchJdbcRepository.StoredExplanation stored) {
}
