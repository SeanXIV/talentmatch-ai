package com.talentmatch.application.render;

import java.util.Objects;

/**
 * The shared, format-neutral layout model (Phase 7 §4.8). {@link CvLayout} and {@link LetterLayout}
 * turn content into blocks; {@link DocxWriter} and {@link PdfWriter} render the same blocks, so the
 * DOCX and the PDF of a version always say the same thing. Texts are raw: the writers clean them
 * ({@link RenderText#clean}).
 */
public sealed interface Block {

    /** The document title (the owner's name on a CV). */
    record Title(String text) implements Block {
        public Title {
            Objects.requireNonNull(text, "text");
        }
    }

    /** One line of contact details, already joined with " · ". */
    record ContactLine(String text) implements Block {
        public ContactLine {
            Objects.requireNonNull(text, "text");
        }
    }

    /** A section heading ("Experience"). */
    record Heading(String text) implements Block {
        public Heading {
            Objects.requireNonNull(text, "text");
        }
    }

    /** A bold entry line ("Backend Engineer — Acme Ltd"), optionally followed by a plain detail line. */
    record Subheading(String text, String detail) implements Block {
        public Subheading {
            Objects.requireNonNull(text, "text");
        }
    }

    /** Body text. */
    record Paragraph(String text) implements Block {
        public Paragraph {
            Objects.requireNonNull(text, "text");
        }
    }

    /** One bullet point (hanging indent). */
    record BulletItem(String text) implements Block {
        public BulletItem {
            Objects.requireNonNull(text, "text");
        }
    }

    /** Extra vertical space between entries. */
    record Spacer() implements Block {
    }

    /** A page footer line (every page). The writers add {@link #DRAFT} themselves when rendering a draft. */
    record Footer(String text) implements Block {

        /** Footer of unapproved exports (decision l). */
        public static final String DRAFT_TEXT = "DRAFT – not approved in TalentMatch";

        public static final Footer DRAFT = new Footer(DRAFT_TEXT);

        public Footer {
            Objects.requireNonNull(text, "text");
        }
    }
}
