package com.talentmatch.web.dto;

import com.talentmatch.feed.FeedSourceService;
import com.talentmatch.feed.source.SourceKind;

/**
 * Body of {@code POST /api/feed/sources}. Unknown fields and unknown {@code kind} values are rejected
 * by the web layer (400 MALFORMED_REQUEST).
 *
 * @param kind                GREENHOUSE, LEVER or ASHBY (ADZUNA is not available yet)
 * @param boardToken          the board name from the provider URL
 * @param companyName         optional; Greenhouse boards fill it from the board check when omitted
 * @param options             optional provider options
 * @param pollIntervalSeconds optional; the kind's default when omitted
 * @param verify              check the board with the provider first (default true)
 */
public record FeedSourceRequest(SourceKind kind, String boardToken, String companyName, Options options,
                                Integer pollIntervalSeconds, Boolean verify) {

    /**
     * @param leverInstance "eu" for jobs.eu.lever.co sites, "global" (default) otherwise
     * @param what          Adzuna search terms (step 9)
     * @param where         Adzuna location (step 9)
     */
    public record Options(String leverInstance, String what, String where) {
    }

    public FeedSourceService.NewSource toNewSource() {
        FeedSourceService.SourceOptions sourceOptions = options == null ? null
                : new FeedSourceService.SourceOptions(options.leverInstance(), options.what(), options.where());
        return new FeedSourceService.NewSource(kind, boardToken, companyName, sourceOptions, pollIntervalSeconds,
                verify);
    }
}
