package com.talentmatch.feed;

import com.talentmatch.feed.source.SourceProperties;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

/**
 * Hands due sources to the poll threads (§4.1). Each tick claims at most as many due sources as
 * there are free poll threads ({@code poll-threads − active}), leasing them for
 * {@code talentmatch.feed.lease} in one short autocommit statement ({@code FOR UPDATE SKIP LOCKED}),
 * then submits each to {@code feedPollExecutor}. A source that can't be submitted gets its lease
 * back and stays due.
 *
 * <p>{@link #tick()} is public so tests can drive polling with the scheduler switched off.
 */
@Component
public class FeedScheduler {

    private static final Logger log = LoggerFactory.getLogger(FeedScheduler.class);

    private final FeedSourceRepository sources;
    private final SourcePoller poller;
    private final ThreadPoolTaskExecutor executor;
    private final FeedProperties properties;
    private final int pollThreads;
    private final Clock clock;

    public FeedScheduler(FeedSourceRepository sources, SourcePoller poller,
                         @Qualifier(FeedConfig.POLL_EXECUTOR) ThreadPoolTaskExecutor executor,
                         FeedProperties properties, SourceProperties sourceProperties, Clock clock) {
        this.sources = sources;
        this.poller = poller;
        this.executor = executor;
        this.properties = properties;
        this.pollThreads = sourceProperties.http().pollThreads();
        this.clock = clock;
    }

    /** The scheduled entry point: {@link #tick()} with every failure logged, never thrown. */
    void scheduledTick() {
        try {
            tick();
        } catch (RuntimeException e) {
            log.warn("Feed scheduler tick failed ({})", SourcePoller.describe(e));
        }
    }

    /**
     * Claims due sources for the free poll threads and submits them.
     *
     * @return the number of polls started (0 when the feed is disabled or every thread is busy)
     */
    public int tick() {
        if (!properties.enabled()) {
            return 0;
        }
        int free = pollThreads - executor.getActiveCount();
        if (free <= 0) {
            return 0;
        }
        Instant now = clock.instant();
        List<FeedSource> claimed = sources.claimDue(free, now, leaseUntil(now));
        int started = 0;
        for (FeedSource source : claimed) {
            if (submit(source)) {
                started++;
            }
        }
        if (!claimed.isEmpty()) {
            log.debug("Feed tick: claimed={} started={}", claimed.size(), started);
        }
        return started;
    }

    /**
     * Runs a poll of a claimed source on a poll thread.
     *
     * @return false when no poll thread was free; the lease is then released and the source made due
     */
    public boolean submit(FeedSource claimed) {
        try {
            executor.execute(() -> poller.poll(claimed));
            return true;
        } catch (RejectedExecutionException e) {
            try {
                sources.release(claimed.id(), claimed.leaseUntil(), true, clock.instant());
            } catch (RuntimeException releaseFailure) {
                log.warn("Feed source={} lease could not be released ({}); it expires on its own", claimed.id(),
                        SourcePoller.describe(releaseFailure));
            }
            log.info("Feed source={} not polled now: every poll thread is busy; it stays due", claimed.id());
            return false;
        }
    }

    /** The lease end for a claim made now (microseconds, the precision PostgreSQL stores). */
    public Instant leaseUntil(Instant now) {
        return now.plus(properties.lease()).truncatedTo(ChronoUnit.MICROS);
    }
}
