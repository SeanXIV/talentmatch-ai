package com.talentmatch.feed.source;

import jakarta.validation.Valid;
import java.net.URI;
import java.time.Duration;
import java.util.Locale;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * The source-layer part of {@code talentmatch.feed.*} (§7): HTTP limits, the posting cap and the
 * provider base URLs. It lives in {@code feed.source} so adapters don't depend on {@code feed}; the
 * rest of {@code talentmatch.feed.*} (scheduler, intervals, closing, skills) is bound separately by
 * {@code FeedProperties}. The two bind disjoint keys under the same prefix.
 *
 * @param maxPostingsPerSource a listing with more postings fails with INVALID_RESPONSE (runaway data)
 */
@Validated
@ConfigurationProperties("talentmatch.feed")
public record SourceProperties(
        @DefaultValue("5000") int maxPostingsPerSource,
        @DefaultValue @Valid Http http,
        @DefaultValue @Valid Greenhouse greenhouse,
        @DefaultValue @Valid Lever lever,
        @DefaultValue @Valid Ashby ashby) {

    public SourceProperties {
        requireRange("talentmatch.feed.max-postings-per-source", maxPostingsPerSource, 1, 50_000);
        http = http == null ? Http.defaults() : http;
        greenhouse = greenhouse == null ? Greenhouse.defaults() : greenhouse;
        lever = lever == null ? Lever.defaults() : lever;
        ashby = ashby == null ? Ashby.defaults() : ashby;
    }

    /** All defaults (the public provider hosts). */
    public static SourceProperties defaults() {
        return new SourceProperties(5000, null, null, null, null);
    }

    /**
     * @param connectTimeout TCP/TLS connect timeout (1s..60s)
     * @param readTimeout    time for the response, body included (1s..60s)
     * @param maxBodyBytes   body cap counted after decompression (1 KB..100 MB); over it → TOO_LARGE
     * @param pollThreads    concurrent source polls (1..16)
     * @param minHostSpacing minimum gap between two requests to the same host (0..60s)
     * @param userAgent      sent on every request; no CR/LF
     */
    public record Http(
            @DefaultValue("5s") Duration connectTimeout,
            @DefaultValue("30s") Duration readTimeout,
            @DefaultValue("20971520") long maxBodyBytes,
            @DefaultValue("2") int pollThreads,
            @DefaultValue("1s") Duration minHostSpacing,
            @DefaultValue("TalentMatch/0.5 (personal job search)") String userAgent) {

        public static final String DEFAULT_USER_AGENT = "TalentMatch/0.5 (personal job search)";

        public Http {
            requireTimeout("talentmatch.feed.http.connect-timeout", connectTimeout);
            requireTimeout("talentmatch.feed.http.read-timeout", readTimeout);
            requireRange("talentmatch.feed.http.max-body-bytes", maxBodyBytes, 1024, 100L * 1024 * 1024);
            requireRange("talentmatch.feed.http.poll-threads", pollThreads, 1, 16);
            if (minHostSpacing == null || minHostSpacing.isNegative()
                    || minHostSpacing.compareTo(Duration.ofSeconds(60)) > 0) {
                throw new IllegalArgumentException("talentmatch.feed.http.min-host-spacing is " + minHostSpacing
                        + " but must be between PT0S and PT1M");
            }
            if (userAgent == null || userAgent.isBlank() || userAgent.indexOf('\r') >= 0
                    || userAgent.indexOf('\n') >= 0 || userAgent.length() > 300) {
                throw new IllegalArgumentException(
                        "talentmatch.feed.http.user-agent must be 1-300 characters on one line");
            }
            userAgent = userAgent.strip();
        }

        public static Http defaults() {
            return new Http(Duration.ofSeconds(5), Duration.ofSeconds(30), 20_971_520L, 2, Duration.ofSeconds(1),
                    DEFAULT_USER_AGENT);
        }
    }

    /**
     * @param baseUrl                 Job Board API host
     * @param maxDetailCallsPerPoll   detail requests per poll for new or changed postings (0..200)
     */
    public record Greenhouse(
            @DefaultValue("https://boards-api.greenhouse.io") URI baseUrl,
            @DefaultValue("20") int maxDetailCallsPerPoll) {

        public Greenhouse {
            baseUrl = requireBaseUrl("talentmatch.feed.greenhouse.base-url", baseUrl);
            requireRange("talentmatch.feed.greenhouse.max-detail-calls-per-poll", maxDetailCallsPerPoll, 0, 200);
        }

        public static Greenhouse defaults() {
            return new Greenhouse(URI.create("https://boards-api.greenhouse.io"), 20);
        }
    }

    /**
     * @param baseUrl   Postings API host (global instance)
     * @param euBaseUrl Postings API host of the EU instance ({@code options.leverInstance = "eu"})
     */
    public record Lever(
            @DefaultValue("https://api.lever.co") URI baseUrl,
            @DefaultValue("https://api.eu.lever.co") URI euBaseUrl) {

        public Lever {
            baseUrl = requireBaseUrl("talentmatch.feed.lever.base-url", baseUrl);
            euBaseUrl = requireBaseUrl("talentmatch.feed.lever.eu-base-url", euBaseUrl);
        }

        public static Lever defaults() {
            return new Lever(URI.create("https://api.lever.co"), URI.create("https://api.eu.lever.co"));
        }
    }

    /** @param baseUrl public posting API host */
    public record Ashby(@DefaultValue("https://api.ashbyhq.com") URI baseUrl) {

        public Ashby {
            baseUrl = requireBaseUrl("talentmatch.feed.ashby.base-url", baseUrl);
        }

        public static Ashby defaults() {
            return new Ashby(URI.create("https://api.ashbyhq.com"));
        }
    }

    private static void requireRange(String name, long value, long min, long max) {
        if (value < min || value > max) {
            throw new IllegalArgumentException(name + " is " + value + " but must be between " + min + " and " + max);
        }
    }

    private static void requireTimeout(String name, Duration value) {
        if (value == null || value.compareTo(Duration.ofSeconds(1)) < 0 || value.compareTo(Duration.ofSeconds(60)) > 0) {
            throw new IllegalArgumentException(name + " is " + value + " but must be between PT1S and PT1M");
        }
    }

    /** http(s), a host, no query or fragment; a trailing '/' is removed so paths can be appended. */
    private static URI requireBaseUrl(String name, URI value) {
        String scheme = value == null || value.getScheme() == null ? null : value.getScheme().toLowerCase(Locale.ROOT);
        if (value == null || !("http".equals(scheme) || "https".equals(scheme)) || value.getHost() == null
                || value.getRawQuery() != null || value.getRawFragment() != null) {
            throw new IllegalArgumentException(name + " must be an http(s) URL with a host and no query, e.g. "
                    + "https://example.com");
        }
        String s = value.toString();
        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        return URI.create(s);
    }
}
