package com.talentmatch.seeker;

/** Which jobs are in scope by origin ({@code origin} query parameter; decision c). */
public enum OriginFilter {
    /** MANUAL and FEED jobs (default). */
    ALL,
    /** Only jobs found by the job feed. */
    FEED,
    /** Only jobs entered through the jobs API (or the ETL). */
    MANUAL
}
