package com.talentmatch.feed.source;

import java.util.Map;
import java.util.Optional;

/**
 * One job-board provider (§4.2, corrected by the step-4 probe findings). Implementations are
 * stateless and thread-safe, make no database calls, and never log a posting's text or a URL query.
 */
public interface SourceAdapter {

    SourceKind kind();

    /**
     * Fetches the current listing.
     *
     * @throws SourceException on any HTTP, network or format failure; never returns null
     */
    FetchResult fetch(SourceTarget target, FetchRequest request);

    /**
     * Checks that a board exists (used when a source is added).
     *
     * @return empty when the provider says the board doesn't exist (NOT_FOUND)
     * @throws SourceException on any other failure
     */
    Optional<BoardInfo> probe(String boardToken, Map<String, String> options);

    /**
     * The full posting when the listing has no description (Greenhouse). The result has
     * {@code descriptionComplete=true} and may carry more data than the listing (salary, offices).
     * Default: not supported, empty.
     *
     * @return empty when not supported, or when the posting no longer exists (NOT_FOUND)
     * @throws SourceException on any other failure
     */
    default Optional<RawPosting> fetchDetail(SourceTarget target, String externalId) {
        return Optional.empty();
    }
}
