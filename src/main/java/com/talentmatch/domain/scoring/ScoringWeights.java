package com.talentmatch.domain.scoring;

/**
 * Points awarded per job skill the candidate has.
 *
 * @param required   points for a required skill (must be &gt; 0)
 * @param niceToHave points for a nice-to-have skill (must be &gt; 0)
 */
public record ScoringWeights(int required, int niceToHave) {

    public ScoringWeights {
        if (required <= 0) {
            throw new IllegalArgumentException("required weight must be > 0, got " + required);
        }
        if (niceToHave <= 0) {
            throw new IllegalArgumentException("nice-to-have weight must be > 0, got " + niceToHave);
        }
    }
}
