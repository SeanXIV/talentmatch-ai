package com.talentmatch.feed;

import com.talentmatch.feed.source.SourceKind;
import com.talentmatch.feed.source.SourceTarget;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * One {@code feed_source} row (V5), read with JDBC (not mapped in JPA).
 *
 * @param options              provider options, e.g. {@code leverInstance=eu} (never null)
 * @param pollIntervalSeconds  the owner's interval, or null for the per-kind default
 * @param lastStatus           the last poll outcome (V5 CHECK values), null before the first poll
 * @param lastError            sanitized: no body, no URL query, no keys
 * @param etag                 internal (conditional requests); never returned by the API
 */
public record FeedSource(
        UUID id,
        String sourceKey,
        SourceKind kind,
        SourceManagedBy managedBy,
        FeedSourceState state,
        String companyName,
        String boardToken,
        Map<String, String> options,
        Integer pollIntervalSeconds,
        Instant nextPollAt,
        Instant leaseUntil,
        Instant lastPolledAt,
        Instant lastSuccessAt,
        String lastStatus,
        String lastError,
        int consecutiveFailures,
        String etag,
        String lastModified,
        String contentHash,
        Instant baselineAt,
        Instant suspiciousSince,
        int openPostings,
        Instant createdAt,
        Instant updatedAt) {

    public FeedSource {
        options = options == null ? Map.of() : Map.copyOf(options);
    }

    /**
     * What an ATS adapter needs to fetch or probe this source ({@code feed.source} doesn't know
     * {@code FeedSource}, so the mapping lives here).
     *
     * @throws IllegalStateException for an Adzuna source (it has no board token)
     */
    public SourceTarget target() {
        if (boardToken == null) {
            throw new IllegalStateException("Feed source " + id + " (" + kind + ") has no board token");
        }
        return new SourceTarget(boardToken, companyName, options);
    }
}
