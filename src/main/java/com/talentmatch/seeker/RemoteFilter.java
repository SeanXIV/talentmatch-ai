package com.talentmatch.seeker;

/** How remote jobs are treated ({@code remote} query parameter; decision e). */
public enum RemoteFilter {
    /** Remote jobs are listed, and pass the country filter whatever their countries (default). */
    INCLUDE,
    /** Only feed jobs whose workplace is REMOTE (MANUAL jobs drop out). */
    ONLY,
    /** No REMOTE jobs. */
    EXCLUDE
}
