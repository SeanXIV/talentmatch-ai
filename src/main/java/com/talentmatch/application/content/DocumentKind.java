package com.talentmatch.application.content;

/** The two tailored documents of an application. Must match V7's {@code application_document.kind}. */
public enum DocumentKind {
    CV,
    COVER_LETTER;

    /** "CV" / "cover letter" (for messages). */
    public String label() {
        return this == CV ? "CV" : "cover letter";
    }
}
