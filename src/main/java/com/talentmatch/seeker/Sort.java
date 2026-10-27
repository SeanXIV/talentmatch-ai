package com.talentmatch.seeker;

/** Order of a candidate's ranked jobs ({@code sort} query parameter; decision k). */
public enum Sort {
    /** Score descending, then {@code listedAt} descending (undated last), then job id (default). */
    SCORE,
    /** {@code listedAt} descending (undated last), then score descending, then job id. */
    NEWEST
}
