package com.talentmatch.ai;

import com.talentmatch.domain.scoring.MatchEvaluation;

/**
 * Extra grounding for seeker explanations (Phase 6 §4.5), applied after
 * {@link MatchExplanationValidator#normalize} accepted the output. It checks the free text, which
 * the validator does not ground. Implemented outside {@code ai} (in {@code seeker}) because it
 * needs the skill vocabulary, and {@code feed.skills} already depends on {@code ai}.
 *
 * <p>Implementations must be thread-safe and must fail closed: when they can't decide (for example
 * the vocabulary can't be loaded) they return {@code false}. If no implementation is available,
 * {@link ExplanationGenerator} rejects every seeker output.
 */
public interface ExplanationGroundingCheck {

    /**
     * @param value    the normalized model output
     * @param eval     the deterministic breakdown the prompt's MATCH FACTS were built from
     * @param jobTitle the job title as stored (numbers in it are allowed)
     * @param company  the company as stored (numbers in it are allowed); may be null
     * @return true to accept the output; false to reject it (template fallback, nothing stored)
     */
    boolean accept(MatchExplanation value, MatchEvaluation eval, String jobTitle, String company);
}
