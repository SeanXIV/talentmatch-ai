package com.talentmatch.service;

/**
 * State of a match's AI explanation. Phase 2 always reports {@link #UNAVAILABLE}
 * (the AI layer arrives in Phase 3).
 */
public enum ExplanationStatus {
    READY,
    PENDING,
    STALE,
    UNAVAILABLE
}
