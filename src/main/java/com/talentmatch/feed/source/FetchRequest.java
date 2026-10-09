package com.talentmatch.feed.source;

/**
 * What the previous successful poll stored for a source (all nullable).
 *
 * @param etag         sent as {@code If-None-Match}; works on Greenhouse, Lever and Ashby (weak ETags)
 * @param lastModified kept for the record type only. No provider sends {@code Last-Modified} and
 *                     none honours {@code If-Modified-Since} (probe findings), so it is never sent.
 * @param lastBodyHash sha256 (hex) of the last body; an identical body counts as not modified
 */
public record FetchRequest(String etag, String lastModified, String lastBodyHash) {

    public static final FetchRequest NONE = new FetchRequest(null, null, null);
}
