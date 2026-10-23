package com.talentmatch.seeker;

/** Where a job's {@code listedAt} comes from (decision d). */
public enum ListedAtSource {
    /** A feed job with a provider date: the earlier of {@code posted_at} and {@code first_seen_at}. */
    POSTED,
    /** A non-baseline feed job without a provider date: {@code first_seen_at}. */
    FIRST_SEEN,
    /** A MANUAL job: {@code job.created_at}. */
    CREATED,
    /** A baseline feed job without a provider date: undated ({@code listedAt} is null). */
    UNKNOWN
}
