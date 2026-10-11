package com.talentmatch.feed.source;

/**
 * The provider behind a feed source. Stored in {@code feed_source.kind} (V5).
 * <ul>
 *   <li>{@link #completeListing()}: a successful fetch returns every open posting, so a posting
 *       missing from it has closed (subject to the suspicious-drop guard).</li>
 *   <li>{@link #ats()}: the company's own applicant tracking system; it beats an aggregator when
 *       choosing the canonical posting of a deduplicated job.</li>
 * </ul>
 */
public enum SourceKind {
    GREENHOUSE(true, true),
    LEVER(true, true),
    ASHBY(true, true),
    /** Aggregator (step 9): a search result is never a complete listing. */
    ADZUNA(false, false);

    private final boolean completeListing;
    private final boolean ats;

    SourceKind(boolean completeListing, boolean ats) {
        this.completeListing = completeListing;
        this.ats = ats;
    }

    public boolean completeListing() {
        return completeListing;
    }

    public boolean ats() {
        return ats;
    }
}
