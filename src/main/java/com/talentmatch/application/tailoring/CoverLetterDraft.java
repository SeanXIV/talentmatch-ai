package com.talentmatch.application.tailoring;

import java.util.List;

/** The model's cover-letter answer (§4.4): the body paragraphs only. May hold nulls (untrusted input). */
public record CoverLetterDraft(List<String> paragraphs) {
}
