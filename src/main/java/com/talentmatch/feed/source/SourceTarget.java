package com.talentmatch.feed.source;

import java.util.Map;
import java.util.Objects;

/**
 * The part of a feed source an adapter needs. {@code feed.source} must not depend on {@code feed}
 * (no package cycle), so the poller maps its {@code FeedSource} row to this record.
 *
 * @param boardToken  the board/site/name token, used as-is (Lever tokens are case-sensitive)
 * @param companyName the owner's name for the company; the fallback when a posting has none (nullable)
 * @param options     provider options, e.g. {@code leverInstance=eu} (never null)
 */
public record SourceTarget(String boardToken, String companyName, Map<String, String> options) {

    public SourceTarget {
        Objects.requireNonNull(boardToken, "boardToken");
        options = options == null ? Map.of() : Map.copyOf(options);
    }

    public SourceTarget(String boardToken, String companyName) {
        this(boardToken, companyName, Map.of());
    }

    /** The company for a posting: the provider's per-posting name, else the owner's, else the token. */
    String company(String fromPosting) {
        if (fromPosting != null && !fromPosting.isBlank()) {
            return fromPosting.strip();
        }
        if (companyName != null && !companyName.isBlank()) {
            return companyName.strip();
        }
        return boardToken;
    }
}
