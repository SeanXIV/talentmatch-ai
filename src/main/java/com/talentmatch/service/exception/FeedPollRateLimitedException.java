package com.talentmatch.service.exception;

import com.talentmatch.web.error.ErrorCode;
import org.springframework.http.HttpStatus;

/** 429 when {@code POST /api/feed/sources/{id}/poll} comes within a minute of the source's last poll. */
public class FeedPollRateLimitedException extends ApiException {

    private static final long serialVersionUID = 1L;

    private final int retryAfterSeconds;

    public FeedPollRateLimitedException(int retryAfterSeconds) {
        super(ErrorCode.FEED_POLL_RATE_LIMITED, HttpStatus.TOO_MANY_REQUESTS, message(Math.max(1, retryAfterSeconds)));
        this.retryAfterSeconds = Math.max(1, retryAfterSeconds);
    }

    /** Seconds until the source may be polled on request again (at least 1); sent as Retry-After. */
    public int getRetryAfterSeconds() {
        return retryAfterSeconds;
    }

    private static String message(int seconds) {
        return "This source was polled less than a minute ago. You can poll it again in " + seconds
                + (seconds == 1 ? " second" : " seconds") + "; its latest result is in GET /api/feed/sources/{id}.";
    }
}
