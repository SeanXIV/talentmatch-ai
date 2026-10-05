package com.talentmatch.ai;

import java.time.Instant;
import java.util.List;

/**
 * The explanation object returned with every match item (never null).
 *
 * @param source      AI or TEMPLATE
 * @param headline    short summary of the fit
 * @param text        the explanation text (equals {@code aiExplanation} when source is AI)
 * @param strengths   short phrases naming matched skills
 * @param gaps        short phrases naming missing skills
 * @param model       provider/model label for AI text, else null
 * @param generatedAt when the AI text was generated, else null
 * @param reason      why a template is shown, null for AI text
 * @param note        user-facing note for {@code reason}, null for AI text
 */
public record ExplanationView(
        ExplanationSource source,
        String headline,
        String text,
        List<String> strengths,
        List<String> gaps,
        String model,
        Instant generatedAt,
        ExplanationReason reason,
        String note) {

    public ExplanationView {
        strengths = strengths == null ? List.of() : List.copyOf(strengths);
        gaps = gaps == null ? List.of() : List.copyOf(gaps);
    }
}
