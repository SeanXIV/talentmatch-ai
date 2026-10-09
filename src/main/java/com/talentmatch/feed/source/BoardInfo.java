package com.talentmatch.feed.source;

/**
 * A board that exists (result of {@link SourceAdapter#probe}).
 *
 * @param companyName   the name the provider gives the board, when it has one (Greenhouse only)
 * @param openPostings  the number of open postings when the probe can tell; null otherwise
 * @param warning       a note for the owner, e.g. "no open postings" (nullable)
 */
public record BoardInfo(String companyName, Integer openPostings, String warning) {

    public static final String NO_OPEN_POSTINGS = "The board exists but has no open postings right now.";
}
