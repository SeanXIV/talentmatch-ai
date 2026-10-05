package com.talentmatch.ai;

/**
 * State of a match's AI explanation (precedence READY &gt; PENDING &gt; STALE &gt; UNAVAILABLE).
 *
 * <ul>
 *   <li>READY: {@code aiExplanation} holds current AI text.</li>
 *   <li>PENDING: an AI explanation is being generated past the request budget; reload shortly.</li>
 *   <li>STALE: an older AI explanation exists but the candidate or job changed since.</li>
 *   <li>UNAVAILABLE: no AI explanation (disabled, not in the top N, or generation failed).</li>
 * </ul>
 * Invariant: {@code aiExplanation != null} iff READY iff {@code explanation.source == AI}.
 */
public enum ExplanationStatus {
    READY,
    PENDING,
    STALE,
    UNAVAILABLE
}
