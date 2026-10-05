package com.talentmatch.ai;

import java.util.Objects;

/**
 * Explanation outcome for one match.
 *
 * @param status        explanation status
 * @param aiExplanation AI text when READY, else null
 * @param explanation   always non-null (AI or template)
 */
public record ExplanationResult(ExplanationStatus status, String aiExplanation, ExplanationView explanation) {

    public ExplanationResult {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(explanation, "explanation");
    }

    /** READY result carrying AI text. */
    public static ExplanationResult ready(ExplanationView aiView) {
        return new ExplanationResult(ExplanationStatus.READY, aiView.text(), aiView);
    }

    /** Non-READY result carrying a template explanation. */
    public static ExplanationResult template(ExplanationStatus status, ExplanationView templateView) {
        if (status == ExplanationStatus.READY) {
            throw new IllegalArgumentException("A template explanation is never READY");
        }
        return new ExplanationResult(status, null, templateView);
    }
}
