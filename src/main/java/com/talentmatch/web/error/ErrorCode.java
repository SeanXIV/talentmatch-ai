package com.talentmatch.web.error;

/** Stable, machine-readable error codes returned in {@link ApiError#code()}. */
public enum ErrorCode {
    // 400
    VALIDATION_FAILED,
    INVALID_PARAMETER,
    INVALID_ID,
    MALFORMED_REQUEST,
    // 404
    CANDIDATE_NOT_FOUND,
    JOB_NOT_FOUND,
    SKILL_NOT_FOUND,
    RECOMPUTE_RUN_NOT_FOUND,
    ENDPOINT_NOT_FOUND,
    // 405 / 406 / 413 / 415
    METHOD_NOT_ALLOWED,
    NOT_ACCEPTABLE,
    PAYLOAD_TOO_LARGE,
    UNSUPPORTED_MEDIA_TYPE,
    // 409
    EMAIL_ALREADY_EXISTS,
    JOB_ALREADY_EXISTS,
    SKILL_ALREADY_EXISTS,
    DATA_CONFLICT,
    RECOMPUTE_ALREADY_RUNNING,
    // 429 (reserved for Phase 3)
    REGENERATE_RATE_LIMITED,
    // 503
    MATCHES_BUSY,
    DATABASE_UNAVAILABLE,
    // 500 / fallbacks
    INTERNAL_ERROR,
    REQUEST_FAILED
}
