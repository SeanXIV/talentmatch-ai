package com.talentmatch.feed;

/**
 * {@code feed_source.managed_by} (V5). OWNER sources are added and edited by hand through
 * {@code /api/feed/sources}; PREFERENCES sources are derived Adzuna queries (step 9), kept in step
 * with the job preferences and read-only through the API.
 */
public enum SourceManagedBy {
    OWNER,
    PREFERENCES
}
