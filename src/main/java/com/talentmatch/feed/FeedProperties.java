package com.talentmatch.feed;

import com.talentmatch.feed.source.SourceKind;
import jakarta.validation.Valid;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * The feed-level part of {@code talentmatch.feed.*} (§7). The source layer (HTTP, provider hosts,
 * posting cap) is bound by {@code feed.source.SourceProperties}; the two bind disjoint keys under the
 * same prefix. Step 5 needs the intervals and the probe timeout; the poller (step 6) adds the
 * scheduler, lease, fresh window and closing keys here.
 *
 * @param intervals    polling cadence per kind (§4.3)
 * @param probeTimeout how long {@code POST /api/feed/sources} waits for the board check (1s..60s)
 */
@Validated
@ConfigurationProperties("talentmatch.feed")
public record FeedProperties(
        @DefaultValue @Valid Intervals intervals,
        @DefaultValue("10s") Duration probeTimeout) {

    /** The V5 CHECK on {@code feed_source.poll_interval_seconds}. */
    public static final int MIN_INTERVAL_SECONDS = 60;
    public static final int MAX_INTERVAL_SECONDS = 86_400;

    public FeedProperties {
        intervals = intervals == null ? Intervals.defaults() : intervals;
        if (probeTimeout == null || probeTimeout.compareTo(Duration.ofSeconds(1)) < 0
                || probeTimeout.compareTo(Duration.ofSeconds(60)) > 0) {
            throw new IllegalArgumentException("talentmatch.feed.probe-timeout is " + probeTimeout
                    + " but must be between PT1S and PT1M");
        }
    }

    public static FeedProperties defaults() {
        return new FeedProperties(Intervals.defaults(), Duration.ofSeconds(10));
    }

    /**
     * @param ats             default interval for company boards (Greenhouse, Lever, Ashby)
     * @param atsMin          the shortest interval an owner may set for a company board
     * @param aggregator      default interval for aggregator queries (Adzuna)
     * @param aggregatorMin   the shortest interval for aggregator queries
     * @param maxBackoff      cap of the exponential failure backoff (§6.1)
     * @param notFoundBackoff wait after NOT_FOUND / UNAUTHORIZED (§6.1)
     */
    public record Intervals(
            @DefaultValue("5m") Duration ats,
            @DefaultValue("2m") Duration atsMin,
            @DefaultValue("15m") Duration aggregator,
            @DefaultValue("10m") Duration aggregatorMin,
            @DefaultValue("1h") Duration maxBackoff,
            @DefaultValue("6h") Duration notFoundBackoff) {

        public Intervals {
            requireInterval("talentmatch.feed.intervals.ats-min", atsMin);
            requireInterval("talentmatch.feed.intervals.aggregator-min", aggregatorMin);
            requireInterval("talentmatch.feed.intervals.ats", ats);
            requireInterval("talentmatch.feed.intervals.aggregator", aggregator);
            if (ats.compareTo(atsMin) < 0) {
                throw new IllegalArgumentException("talentmatch.feed.intervals.ats (" + ats
                        + ") must not be shorter than ats-min (" + atsMin + ")");
            }
            if (aggregator.compareTo(aggregatorMin) < 0) {
                throw new IllegalArgumentException("talentmatch.feed.intervals.aggregator (" + aggregator
                        + ") must not be shorter than aggregator-min (" + aggregatorMin + ")");
            }
            requireInterval("talentmatch.feed.intervals.max-backoff", maxBackoff);
            requireInterval("talentmatch.feed.intervals.not-found-backoff", notFoundBackoff);
        }

        public static Intervals defaults() {
            return new Intervals(Duration.ofMinutes(5), Duration.ofMinutes(2), Duration.ofMinutes(15),
                    Duration.ofMinutes(10), Duration.ofHours(1), Duration.ofHours(6));
        }

        /** The shortest interval (seconds) an owner may set for this kind. */
        public int minSeconds(SourceKind kind) {
            return (int) (kind.ats() ? atsMin : aggregatorMin).toSeconds();
        }

        /** The default interval (seconds) for this kind. */
        public int defaultSeconds(SourceKind kind) {
            return (int) (kind.ats() ? ats : aggregator).toSeconds();
        }

        /**
         * The interval actually used: the configured one (or the kind's default), never below the
         * kind's minimum. Step 9 widens Adzuna sources further to fit the daily request budget.
         */
        public int effectiveSeconds(SourceKind kind, Integer configuredSeconds) {
            int configured = configuredSeconds == null ? defaultSeconds(kind) : configuredSeconds;
            return Math.max(configured, minSeconds(kind));
        }

        private static void requireInterval(String name, Duration value) {
            if (value == null || value.toSeconds() < MIN_INTERVAL_SECONDS || value.toSeconds() > MAX_INTERVAL_SECONDS) {
                throw new IllegalArgumentException(name + " is " + value + " but must be between PT1M and PT24H");
            }
        }
    }
}
