package com.talentmatch.ai;

/**
 * Who an AI explanation is written for. Each audience has its own prompt, input hash and storage
 * columns on {@code job_match}: RECRUITER uses the V3 columns, SEEKER the V6
 * {@code seeker_explanation_*} columns. The two never overwrite each other.
 */
public enum Audience {

    /** A recruiter reading {@code GET /api/jobs/{id}/matches} (Phase 3). */
    RECRUITER("recruiter"),

    /** The job seeker reading {@code GET /api/candidates/{id}/matches} (Phase 6, second person). */
    SEEKER("seeker");

    private final String tag;

    Audience(String tag) {
        this.tag = tag;
    }

    /** Lower-case label for metric tags and log lines. */
    public String tag() {
        return tag;
    }
}
