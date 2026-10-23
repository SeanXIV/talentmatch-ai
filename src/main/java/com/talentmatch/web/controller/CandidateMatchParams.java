package com.talentmatch.web.controller;

import com.talentmatch.preferences.Gazetteer;
import com.talentmatch.seeker.CandidateMatchQuery;
import com.talentmatch.seeker.OriginFilter;
import com.talentmatch.seeker.RemoteFilter;
import com.talentmatch.seeker.Sort;
import com.talentmatch.service.exception.InvalidParameterException;
import java.text.Normalizer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Pure parsing and validation of the query parameters of {@code GET /api/candidates/{id}/matches}
 * (§5.1, §5.2). Every failure is a 400 INVALID_PARAMETER ({@link InvalidParameterException}) naming the
 * parameter; the first invalid parameter (in {@link Raw}'s order) is reported. A missing or blank text
 * parameter means "use the default", like Spring's {@code defaultValue} for the other parameters.
 */
public final class CandidateMatchParams {

    public static final String SORT_MESSAGE = "sort must be score or newest.";
    public static final String POSTED_SINCE_MESSAGE = "postedSince must be an ISO-8601 instant like "
            + "2026-10-16T00:00:00Z or a duration like P2D (at most P366D).";
    public static final String TOO_MANY_COUNTRIES_MESSAGE = "Use at most 20 country codes.";
    public static final String REMOTE_MESSAGE = "remote must be include, only or exclude.";
    public static final String ORIGIN_MESSAGE = "origin must be all, feed or manual.";
    public static final String LOCATION_MESSAGE = "location must be 2 to 100 characters.";
    public static final String PAGE_TOO_LARGE_MESSAGE = "page is too large for this page size.";

    public static final int MAX_COUNTRIES = 20;
    public static final Duration MAX_POSTED_SINCE = Duration.ofDays(366);
    public static final int MIN_LOCATION = 2;
    public static final int MAX_LOCATION = 100;

    private static final Pattern WHITESPACE_RUN = Pattern.compile("\\s+", Pattern.UNICODE_CHARACTER_CLASS);
    private static final int MAX_ECHO = 16;

    private CandidateMatchParams() {
    }

    /**
     * The request's parameters as bound by Spring (numbers and booleans already typed).
     *
     * @param country every {@code country} value (repeated and/or comma-separated)
     */
    public record Raw(Integer limit, Integer page, Double minScore, String sort, String postedSince,
                      List<String> country, String remote, String location, String origin, boolean includeClosed,
                      boolean includeFiltered) {
    }

    /** Validated query plus paging. */
    public record Parsed(CandidateMatchQuery query, int page, int limit) {
    }

    /**
     * @param defaultLimit limit when none is given ({@code talentmatch.matches.default-limit})
     * @param maxLimit     largest limit ({@code talentmatch.matches.max-limit})
     * @param clock        "now" for duration values of {@code postedSince}
     * @throws InvalidParameterException for the first invalid parameter
     */
    public static Parsed parse(Raw raw, int defaultLimit, int maxLimit, Clock clock) {
        int limit = limit(raw.limit(), defaultLimit, maxLimit);
        int page = page(raw.page(), limit);
        double minScore = minScore(raw.minScore());
        Sort sort = sort(raw.sort());
        Instant postedSince = postedSince(raw.postedSince(), clock);
        List<String> countries = countries(raw.country());
        RemoteFilter remote = remote(raw.remote());
        String location = location(raw.location());
        OriginFilter origin = origin(raw.origin());
        CandidateMatchQuery query = new CandidateMatchQuery(origin, postedSince, countries, remote, location, minScore,
                sort, raw.includeClosed(), raw.includeFiltered());
        return new Parsed(query, page, limit);
    }

    public static int limit(Integer limit, int defaultLimit, int maxLimit) {
        int effective = limit == null ? defaultLimit : limit;
        if (effective < 1 || effective > maxLimit) {
            throw new InvalidParameterException("limit", "limit must be between 1 and " + maxLimit + ".");
        }
        return effective;
    }

    /** @param limit the validated limit (for the overflow check) */
    public static int page(Integer page, int limit) {
        int p = page == null ? 0 : page;
        if (p < 0) {
            throw new InvalidParameterException("page", ApiParams.PAGE_MESSAGE);
        }
        if ((long) p * limit > Integer.MAX_VALUE) {
            throw new InvalidParameterException("page", PAGE_TOO_LARGE_MESSAGE);
        }
        return p;
    }

    public static double minScore(Double minScore) {
        double m = minScore == null ? 0d : minScore;
        if (Double.isNaN(m) || m < 0 || m > 1) {
            throw new InvalidParameterException("minScore", ApiParams.MIN_SCORE_MESSAGE);
        }
        return m;
    }

    public static Sort sort(String value) {
        String v = blankToNull(value);
        if (v == null) {
            return Sort.SCORE;
        }
        return switch (v.toLowerCase(Locale.ROOT)) {
            case "score" -> Sort.SCORE;
            case "newest" -> Sort.NEWEST;
            default -> throw new InvalidParameterException("sort", SORT_MESSAGE);
        };
    }

    /**
     * An ISO-8601 instant ({@code 2026-10-16T00:00:00Z}, an offset is accepted) or a positive ISO-8601
     * duration of at most {@link #MAX_POSTED_SINCE} ({@code P2D}, {@code PT6H}), meaning now minus it.
     *
     * @return null when absent
     */
    public static Instant postedSince(String value, Clock clock) {
        String v = blankToNull(value);
        if (v == null) {
            return null;
        }
        char first = v.charAt(0);
        if (first == 'P' || first == 'p' || first == '-' || first == '+') {
            Duration d;
            try {
                d = Duration.parse(v);
            } catch (DateTimeParseException e) {
                throw new InvalidParameterException("postedSince", POSTED_SINCE_MESSAGE);
            }
            if (d.isNegative() || d.isZero() || d.compareTo(MAX_POSTED_SINCE) > 0) {
                throw new InvalidParameterException("postedSince", POSTED_SINCE_MESSAGE);
            }
            return clock.instant().minus(d);
        }
        try {
            return Instant.parse(v);
        } catch (DateTimeParseException e) {
            throw new InvalidParameterException("postedSince", POSTED_SINCE_MESSAGE);
        }
    }

    /**
     * ISO 3166 alpha-2 codes from repeated and/or comma-separated values, upper-cased, distinct, in
     * order. Empty items are ignored; no codes at all means no country filter.
     */
    public static List<String> countries(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        Set<String> codes = new LinkedHashSet<>();
        for (String value : values) {
            if (value == null) {
                continue;
            }
            for (String item : value.split(",", -1)) {
                String raw = item.strip();
                if (raw.isEmpty()) {
                    continue;
                }
                String code = raw.toUpperCase(Locale.ROOT);
                if (!Gazetteer.isCountryCode(code)) {
                    throw new InvalidParameterException("country", "'" + echo(raw) + "' is not an ISO country code.");
                }
                codes.add(code);
                if (codes.size() > MAX_COUNTRIES) {
                    throw new InvalidParameterException("country", TOO_MANY_COUNTRIES_MESSAGE);
                }
            }
        }
        return List.copyOf(new ArrayList<>(codes));
    }

    public static RemoteFilter remote(String value) {
        String v = blankToNull(value);
        if (v == null) {
            return RemoteFilter.INCLUDE;
        }
        return switch (v.toLowerCase(Locale.ROOT)) {
            case "include" -> RemoteFilter.INCLUDE;
            case "only" -> RemoteFilter.ONLY;
            case "exclude" -> RemoteFilter.EXCLUDE;
            default -> throw new InvalidParameterException("remote", REMOTE_MESSAGE);
        };
    }

    /**
     * NFKC, trimmed, whitespace runs collapsed to one space; 2 to 100 characters (code points).
     *
     * @return null when absent or blank
     */
    public static String location(String value) {
        if (value == null) {
            return null;
        }
        String v = WHITESPACE_RUN.matcher(Normalizer.normalize(value, Normalizer.Form.NFKC).strip()).replaceAll(" ");
        if (v.isEmpty()) {
            return null;
        }
        int length = v.codePointCount(0, v.length());
        if (length < MIN_LOCATION || length > MAX_LOCATION) {
            throw new InvalidParameterException("location", LOCATION_MESSAGE);
        }
        return v;
    }

    public static OriginFilter origin(String value) {
        String v = blankToNull(value);
        if (v == null) {
            return OriginFilter.ALL;
        }
        return switch (v.toLowerCase(Locale.ROOT)) {
            case "all" -> OriginFilter.ALL;
            case "feed" -> OriginFilter.FEED;
            case "manual" -> OriginFilter.MANUAL;
            default -> throw new InvalidParameterException("origin", ORIGIN_MESSAGE);
        };
    }

    private static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String v = value.strip();
        return v.isEmpty() ? null : v;
    }

    private static String echo(String value) {
        return value.length() <= MAX_ECHO ? value : value.substring(0, MAX_ECHO) + "...";
    }
}
