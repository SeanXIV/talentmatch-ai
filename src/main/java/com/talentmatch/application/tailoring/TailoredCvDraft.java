package com.talentmatch.application.tailoring;

import java.util.List;

/**
 * The model's CV answer (§4.3): only refs, chosen skill names and rephrased texts. Every fact is
 * copied from the profile by {@link TailoredCvAssembler}. Fields and lists may be null (untrusted
 * input); the assembler handles that.
 *
 * @param headline       null keeps the profile's headline
 * @param summary        null keeps the profile's summary
 * @param skills         profile skill names, most relevant first
 * @param experience     role refs ({@code E1}) with chosen highlights
 * @param projects       project refs ({@code P1}) with chosen highlights
 * @param certifications certification refs ({@code C1})
 */
public record TailoredCvDraft(String headline, String summary, List<String> skills, List<Entry> experience,
                              List<Entry> projects, List<String> certifications) {

    /** A role or project the model chose, with its highlights in the model's order. */
    public record Entry(String ref, List<Highlight> highlights) {
    }

    /** A highlight ref ({@code E1.H2}) and its reworded text. */
    public record Highlight(String ref, String text) {
    }
}
