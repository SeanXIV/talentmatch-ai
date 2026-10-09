package com.talentmatch.feed;

import com.talentmatch.feed.source.SourceKind;
import jakarta.validation.Valid;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * The feed-level part of {@code talentmatch.feed.*} (§7). The source layer (HTTP, provider hosts,
 * posting cap, poll threads) is bound by {@code feed.source.SourceProperties}; the two bind disjoint
 * keys under the same prefix.
 *
 * @param enabled      false: no scheduler and no polling; the endpoints still answer (poll → 409 FEED_DISABLED)
 * @param scheduler    the poll scheduler (§4.1); off in tests ({@code scheduler.enabled=false})
 * @param intervals    polling cadence per kind (§4.3)
 * @param probeTimeout how long {@code POST /api/feed/sources} waits for the board check (1s..60s)
 * @param freshWindow  how old a posting may be and still count as new: the first poll of a source
 *                     marks older postings as baseline (1h..7d)
 * @param lease        how long a claimed source stays leased to one poll (1m..1h); must outlast the
 *                     HTTP deadline (checked by {@code SourcePoller})
 * @param closing      the suspicious-drop guard (§4.5)
 * @param processor    the feed-job processor (§1.2 step 7): sweep interval, batch size, retry delay
 */
@Validated
@ConfigurationProperties("talentmatch.feed")
public record FeedProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue @Valid Scheduler scheduler,
        @DefaultValue @Valid Intervals intervals,
        @DefaultValue("10s") Duration probeTimeout,
        @DefaultValue("24h") Duration freshWindow,
        @DefaultValue("5m") Duration lease,
        @DefaultValue @Valid Closing closing,
        @DefaultValue @Valid Processor processor) {

    /** The V5 CHECK on {@code feed_source.poll_interval_seconds}. */
    public static final int MIN_INTERVAL_SECONDS = 60;
    public static final int MAX_INTERVAL_SECONDS = 86_400;

    static final Duration DEFAULT_FRESH_WINDOW = Duration.ofHours(24);
    static final Duration DEFAULT_LEASE = Duration.ofMinutes(5);

    @ConstructorBinding
    public FeedProperties {
        scheduler = scheduler == null ? Scheduler.defaults() : scheduler;
        intervals = intervals == null ? Intervals.defaults() : intervals;
        closing = closing == null ? Closing.defaults() : closing;
        if (probeTimeout == null || probeTimeout.compareTo(Duration.ofSeconds(1)) < 0
                || probeTimeout.compareTo(Duration.ofSeconds(60)) > 0) {
            throw new IllegalArgumentException("talentmatch.feed.probe-timeout is " + probeTimeout
                    + " but must be between PT1S and PT1M");
        }
        freshWindow = freshWindow == null ? DEFAULT_FRESH_WINDOW : freshWindow;
        requireBetween("talentmatch.feed.fresh-window", freshWindow, Duration.ofHours(1), Duration.ofDays(7));
        lease = lease == null ? DEFAULT_LEASE : lease;
        requireBetween("talentmatch.feed.lease", lease, Duration.ofMinutes(1), Duration.ofHours(1));
        processor = processor == null ? Processor.defaults() : processor;
    }

    /** Step-6 shape (no processor settings); the processor takes its defaults. */
    public FeedProperties(boolean enabled, Scheduler scheduler, Intervals intervals, Duration probeTimeout,
                          Duration freshWindow, Duration lease, Closing closing) {
        this(enabled, scheduler, intervals, probeTimeout, freshWindow, lease, closing, null);
    }

    /** Step-5 shape (intervals and probe timeout); everything else takes its default. */
    public FeedProperties(Intervals intervals, Duration probeTimeout) {
        this(true, null, intervals, probeTimeout, null, null, null);
    }

    public static FeedProperties defaults() {
        return new FeedProperties(Intervals.defaults(), Duration.ofSeconds(10));
    }

    /** True when the scheduler actually runs: the feed and the scheduler are both enabled. */
    public boolean schedulerRunning() {
        return enabled && scheduler.enabled();
    }

    /**
     * @param enabled      false: no automatic polling (tests drive {@code FeedScheduler.tick()} themselves)
     * @param tick         delay between two scheduler ticks (1s..10m)
     * @param initialDelay delay before the first tick after startup (0..10m)
     */
    public record Scheduler(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("15s") Duration tick,
            @DefaultValue("20s") Duration initialDelay) {

        public Scheduler {
            tick = tick == null ? Duration.ofSeconds(15) : tick;
            initialDelay = initialDelay == null ? Duration.ofSeconds(20) : initialDelay;
            requireBetween("talentmatch.feed.scheduler.tick", tick, Duration.ofSeconds(1), Duration.ofMinutes(10));
            requireBetween("talentmatch.feed.scheduler.initial-delay", initialDelay, Duration.ZERO,
                    Duration.ofMinutes(10));
        }

        public static Scheduler defaults() {
            return new Scheduler(true, Duration.ofSeconds(15), Duration.ofSeconds(20));
        }
    }

    /**
     * @param suspiciousDropRatio a complete listing missing more than this share of the open postings
     *                            (with at least 6 open) is suspicious: closing waits for a second such
     *                            poll (0.1..0.95)
     */
    public record Closing(@DefaultValue("0.5") double suspiciousDropRatio) {

        public Closing {
            if (Double.isNaN(suspiciousDropRatio) || suspiciousDropRatio < 0.1 || suspiciousDropRatio > 0.95) {
                throw new IllegalArgumentException("talentmatch.feed.closing.suspicious-drop-ratio is "
                        + suspiciousDropRatio + " but must be between 0.1 and 0.95");
            }
        }

        public static Closing defaults() {
            return new Closing(0.5);
        }
    }

    /**
     * The feed-job processor (dictionary skills, preference filter, score, notification row). It runs
     * in the background only while the scheduler runs ({@link #schedulerRunning()}); with the scheduler
     * off (tests), nothing processes until {@code FeedProcessor.processDue()} is called.
     *
     * @param sweep      how often the processor looks for due jobs without being woken (1s..10m)
     * @param batchSize  jobs claimed per round (1..500)
     * @param retryDelay how long a job whose processing failed waits before the next try (1s..1h)
     */
    public record Processor(
            @DefaultValue("30s") Duration sweep,
            @DefaultValue("50") int batchSize,
            @DefaultValue("1m") Duration retryDelay) {

        public Processor {
            sweep = sweep == null ? Duration.ofSeconds(30) : sweep;
            retryDelay = retryDelay == null ? Duration.ofMinutes(1) : retryDelay;
            requireBetween("talentmatch.feed.processor.sweep", sweep, Duration.ofSeconds(1), Duration.ofMinutes(10));
            if (batchSize < 1 || batchSize > 500) {
                throw new IllegalArgumentException("talentmatch.feed.processor.batch-size is " + batchSize
                        + " but must be between 1 and 500");
            }
            requireBetween("talentmatch.feed.processor.retry-delay", retryDelay, Duration.ofSeconds(1),
                    Duration.ofHours(1));
        }

        public static Processor defaults() {
            return new Processor(Duration.ofSeconds(30), 50, Duration.ofMinutes(1));
        }
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

    private static void requireBetween(String name, Duration value, Duration min, Duration max) {
        if (value == null || value.compareTo(min) < 0 || value.compareTo(max) > 0) {
            throw new IllegalArgumentException(name + " is " + value + " but must be between " + min + " and " + max);
        }
    }
}
