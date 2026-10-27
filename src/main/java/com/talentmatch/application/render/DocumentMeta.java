package com.talentmatch.application.render;

import java.time.Instant;
import java.util.Objects;

/**
 * Document properties written by both writers (DOCX core properties, PDF document information).
 *
 * @param title   "CV – {name}" / "Cover letter – {name}"
 * @param author  the owner's full name (may be blank)
 * @param created the version's {@code created_at}
 */
public record DocumentMeta(String title, String author, Instant created) {

    public DocumentMeta {
        Objects.requireNonNull(title, "title");
        author = author == null ? "" : author;
        Objects.requireNonNull(created, "created");
    }
}
