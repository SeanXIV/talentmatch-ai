package com.talentmatch.feed.source;

import java.util.List;

/**
 * The outcome of one successful listing fetch.
 *
 * @param notModified  304, or a body identical to the last one; {@code postings} is then empty
 * @param etag         the ETag to store for the next request (nullable)
 * @param lastModified the provider's {@code Last-Modified}, if any (nullable; informational only)
 * @param bodyHash     sha256 (hex) of the decompressed body (nullable on a 304)
 * @param complete     the listing holds every open posting ({@link SourceKind#completeListing()})
 * @param postings     the mapped postings (unlisted ones are already left out)
 * @param skipped      postings that could not be mapped (no id, no title, no http(s) URL, bad data)
 */
public record FetchResult(boolean notModified, String etag, String lastModified, String bodyHash,
                          boolean complete, List<RawPosting> postings, int skipped) {

    public FetchResult {
        postings = postings == null ? List.of() : List.copyOf(postings);
    }

    static FetchResult notModified(SourceResponse response, boolean complete) {
        return new FetchResult(true, response.etag(), response.lastModified(), response.bodyHash(), complete,
                List.of(), 0);
    }
}
