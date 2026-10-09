package com.talentmatch.feed.source;

/**
 * A successful (2xx or 304) response from {@link SourceHttpClient}.
 *
 * @param notModified  304, or the body hash equals {@link FetchRequest#lastBodyHash()}; {@code body} is then null
 * @param status       the HTTP status (200..299 or 304)
 * @param body         the decompressed body, at most {@code max-body-bytes}; null when not modified
 * @param etag         the response ETag, or the stored one on a 304 without one (nullable)
 * @param lastModified the response {@code Last-Modified} (nullable; informational only)
 * @param bodyHash     sha256 (hex) of {@code body}; on a 304, the stored hash (nullable)
 */
public record SourceResponse(boolean notModified, int status, byte[] body, String etag, String lastModified,
                             String bodyHash) {
}
