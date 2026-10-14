package com.talentmatch.service.exception;

import com.talentmatch.web.error.ErrorCode;
import org.springframework.http.HttpStatus;

/**
 * 429 for {@code POST /api/feed/sources/lookup} while another lookup runs, or within the minimum interval
 * ({@code talentmatch.feed.lookup.min-interval}) of the last one.
 */
public class FeedLookupRateLimitedException extends ApiException {

    private static final long serialVersionUID = 1L;

    private final int retryAfterSeconds;
    private final boolean busy;

    /**
     * @param retryAfterSeconds seconds until the next lookup may start (at least 1)
     * @param busy              true: another lookup is running; false: the last one started too recently
     * @param intervalSeconds   the configured minimum interval, for the message
     */
    public FeedLookupRateLimitedException(int retryAfterSeconds, boolean busy, int intervalSeconds) {
        super(ErrorCode.FEED_LOOKUP_RATE_LIMITED, HttpStatus.TOO_MANY_REQUESTS,
                message(Math.max(1, retryAfterSeconds), busy, Math.max(1, intervalSeconds)));
        this.retryAfterSeconds = Math.max(1, retryAfterSeconds);
        this.busy = busy;
    }

    /** Seconds until the next lookup may start (at least 1); sent as Retry-After. */
    public int getRetryAfterSeconds() {
        return retryAfterSeconds;
    }

    /** True when another lookup was running. */
    public boolean isBusy() {
        return busy;
    }

    private static String message(int seconds, boolean busy, int interval) {
        String retry = "try again in " + seconds + (seconds == 1 ? " second." : " seconds.");
        return busy
                ? "Another company lookup is running; " + retry
                : "A company lookup ran less than " + interval + (interval == 1 ? " second" : " seconds") + " ago; "
                        + retry;
    }
}
