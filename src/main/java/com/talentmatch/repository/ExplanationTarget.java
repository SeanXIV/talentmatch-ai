package com.talentmatch.repository;

/**
 * Which set of explanation columns on {@code job_match} a guarded write targets. RECRUITER is the V3
 * set ({@code ai_explanation}, {@code explanation_*}); SEEKER is the V6 set
 * ({@code seeker_explanation_*}). A write to one never touches the other.
 *
 * <p>Kept in {@code repository} so this package does not depend on {@code ai}; {@code ai.Audience}
 * maps onto it.
 */
public enum ExplanationTarget {

    /** V3 columns, written for {@code GET /api/jobs/{id}/matches}. */
    RECRUITER,

    /** V6 {@code seeker_explanation_*} columns, written for {@code GET /api/candidates/{id}/matches}. */
    SEEKER
}
