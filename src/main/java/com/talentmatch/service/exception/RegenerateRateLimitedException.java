package com.talentmatch.service.exception;

import com.talentmatch.web.error.ErrorCode;
import org.springframework.http.HttpStatus;

/** 429 when {@code regenerate=true} is repeated for a job within the regenerate window. */
public class RegenerateRateLimitedException extends ApiException {

    private static final long serialVersionUID = 1L;

    private final int retryAfterSeconds;

    public RegenerateRateLimitedException(int retryAfterSeconds) {
        super(ErrorCode.REGENERATE_RATE_LIMITED, HttpStatus.TOO_MANY_REQUESTS, message(Math.max(1, retryAfterSeconds)));
        this.retryAfterSeconds = Math.max(1, retryAfterSeconds);
    }

    /** Seconds until the job can be regenerated again (at least 1); sent as Retry-After. */
    public int getRetryAfterSeconds() {
        return retryAfterSeconds;
    }

    private static String message(int seconds) {
        return "Matches for this job were regenerated recently. You can regenerate again in " + seconds
                + (seconds == 1 ? " second" : " seconds")
                + "; reload without regenerate=true to see the current results.";
    }
}
