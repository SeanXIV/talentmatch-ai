package com.talentmatch.domain.scoring;

import java.util.List;

/**
 * Deterministic result of scoring one candidate against one job.
 *
 * @param score             earnedPoints / maxPoints, in [0, 1]
 * @param earnedPoints      points for job skills the candidate has
 * @param maxPoints         points for all job skills
 * @param matchedRequired   required skills the candidate has (sorted by name, then id)
 * @param matchedNiceToHave nice-to-have skills the candidate has
 * @param missingRequired   required skills the candidate lacks
 * @param missingNiceToHave nice-to-have skills the candidate lacks
 * @param summary           one-line human-readable summary
 */
public record MatchEvaluation(
        double score,
        int earnedPoints,
        int maxPoints,
        List<SkillHit> matchedRequired,
        List<SkillHit> matchedNiceToHave,
        List<SkillHit> missingRequired,
        List<SkillHit> missingNiceToHave,
        String summary) {

    public MatchEvaluation {
        matchedRequired = List.copyOf(matchedRequired);
        matchedNiceToHave = List.copyOf(matchedNiceToHave);
        missingRequired = List.copyOf(missingRequired);
        missingNiceToHave = List.copyOf(missingNiceToHave);
    }
}
