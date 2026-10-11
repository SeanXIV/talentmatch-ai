package com.talentmatch.feed;

import com.talentmatch.service.exception.ConflictException;
import com.talentmatch.service.exception.FeedPollRateLimitedException;
import com.talentmatch.service.exception.NotFoundException;
import com.talentmatch.web.error.ErrorCode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * {@code POST /api/feed/sources/{id}/poll} (§5.1): poll one source now, whatever its schedule (a
 * paused source too). The poll runs on a poll thread; the request only claims and queues it.
 * <ul>
 *   <li>409 FEED_DISABLED when {@code talentmatch.feed.enabled=false}.</li>
 *   <li>404 FEED_SOURCE_NOT_FOUND.</li>
 *   <li>409 FEED_POLL_IN_PROGRESS while another poll holds the source's lease.</li>
 *   <li>429 FEED_POLL_RATE_LIMITED (Retry-After) when the source was polled less than a minute ago.</li>
 * </ul>
 * When every poll thread is busy, the source is made due instead and the next scheduler tick polls
 * it; the response is still 202.
 */
@Service
public class FeedPollService {

    private static final Logger log = LoggerFactory.getLogger(FeedPollService.class);

    /** The shortest gap between the source's last poll and a poll on request. */
    static final Duration MIN_GAP = Duration.ofSeconds(60);

    private final FeedSourceRepository sources;
    private final FeedScheduler scheduler;
    private final FeedProperties properties;
    private final Clock clock;

    public FeedPollService(FeedSourceRepository sources, FeedScheduler scheduler, FeedProperties properties,
                           Clock clock) {
        this.sources = sources;
        this.scheduler = scheduler;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * @param polling true when the poll started on a poll thread; false when every thread was busy and
     *                the source was made due for the next tick instead
     */
    public record Queued(UUID sourceId, boolean polling) {
    }

    public Queued pollNow(UUID id) {
        if (!properties.enabled()) {
            throw new ConflictException(ErrorCode.FEED_DISABLED, "The job feed is switched off "
                    + "(talentmatch.feed.enabled=false, env FEED_ENABLED), so sources aren't polled. Turn it on and "
                    + "restart to poll.");
        }
        Instant now = clock.instant();
        FeedSource source = sources.findById(id).orElseThrow(() -> NotFoundException.feedSource(id));
        if (source.leaseUntil() != null && source.leaseUntil().isAfter(now)) {
            throw inProgress(id);
        }
        if (source.lastPolledAt() != null) {
            Duration since = Duration.between(source.lastPolledAt(), now);
            if (since.compareTo(MIN_GAP) < 0) {
                long millisLeft = MIN_GAP.minus(since).toMillis();
                throw new FeedPollRateLimitedException((int) Math.max(1, (millisLeft + 999) / 1000));
            }
        }

        Optional<FeedSource> claimed = sources.claim(id, now, scheduler.leaseUntil(now));
        if (claimed.isEmpty()) {
            // Claimed by a tick in the meantime, or deleted.
            if (sources.findById(id).isEmpty()) {
                throw NotFoundException.feedSource(id);
            }
            throw inProgress(id);
        }
        boolean polling = scheduler.submit(claimed.get());
        log.info("Feed poll requested source={} kind={} started={}", id, source.kind(), polling);
        return new Queued(id, polling);
    }

    private static ConflictException inProgress(UUID id) {
        return new ConflictException(ErrorCode.FEED_POLL_IN_PROGRESS, "Source " + id + " is being polled right now. "
                + "Check its lastStatus with GET /api/feed/sources/" + id + " in a moment.");
    }
}
