package com.talentmatch.service.exception;

import com.talentmatch.web.error.ErrorCode;
import org.springframework.http.HttpStatus;

/** 429 when {@code POST /api/notifications/test} comes within the test interval (30s) of the last attempt. */
public class NotificationTestRateLimitedException extends ApiException {

    private static final long serialVersionUID = 1L;

    private final int retryAfterSeconds;

    /**
     * @param retryAfterSeconds seconds until the next test is allowed (at least 1)
     * @param intervalSeconds   the configured test interval, for the message
     */
    public NotificationTestRateLimitedException(int retryAfterSeconds, int intervalSeconds) {
        super(ErrorCode.NOTIFICATION_TEST_RATE_LIMITED, HttpStatus.TOO_MANY_REQUESTS,
                message(Math.max(1, retryAfterSeconds), Math.max(1, intervalSeconds)));
        this.retryAfterSeconds = Math.max(1, retryAfterSeconds);
    }

    /** Seconds until the next test is allowed (at least 1); sent as Retry-After. */
    public int getRetryAfterSeconds() {
        return retryAfterSeconds;
    }

    private static String message(int seconds, int interval) {
        return "A test email was sent less than " + interval + (interval == 1 ? " second" : " seconds")
                + " ago. Try again in " + seconds + (seconds == 1 ? " second." : " seconds.");
    }
}
