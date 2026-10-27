package com.talentmatch.seeker;

import java.time.Instant;
import java.util.List;

/**
 * The validated scope, threshold and order of {@code GET /api/candidates/{id}/matches} (§5.1). Paging
 * is separate. Null enum values become their defaults; {@code countries} is never null.
 *
 * @param origin          which jobs by origin (default ALL)
 * @param postedSince     only jobs whose {@code listedAt} is at or after this instant; null = no limit
 *                        (undated jobs are excluded when set)
 * @param countries       ISO 3166 alpha-2 codes, upper case, distinct; empty = no country filter
 * @param remote          remote handling (default INCLUDE)
 * @param location        normalized, case-insensitive substring of the feed location text; null = none
 * @param minScore        listed jobs score at least this, in [0, 1]
 * @param sort            order (default SCORE)
 * @param includeClosed   also list closed feed jobs
 * @param includeFiltered also list jobs the owner's preferences filtered out (owner only)
 */
public record CandidateMatchQuery(OriginFilter origin, Instant postedSince, List<String> countries,
                                  RemoteFilter remote, String location, double minScore, Sort sort,
                                  boolean includeClosed, boolean includeFiltered) {

    public CandidateMatchQuery {
        origin = origin == null ? OriginFilter.ALL : origin;
        countries = countries == null ? List.of() : List.copyOf(countries);
        remote = remote == null ? RemoteFilter.INCLUDE : remote;
        sort = sort == null ? Sort.SCORE : sort;
        if (Double.isNaN(minScore) || minScore < 0 || minScore > 1) {
            throw new IllegalArgumentException("minScore must be between 0 and 1");
        }
    }

    /** Every default: all origins, no date, country or location filter, minScore 0, sort by score. */
    public static CandidateMatchQuery defaults() {
        return new CandidateMatchQuery(OriginFilter.ALL, null, List.of(), RemoteFilter.INCLUDE, null, 0d,
                Sort.SCORE, false, false);
    }
}
