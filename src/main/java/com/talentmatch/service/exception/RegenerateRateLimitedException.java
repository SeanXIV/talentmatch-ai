package com.talentmatch.service.exception;

import com.talentmatch.web.error.ErrorCode;
import org.springframework.http.HttpStatus;

/**
 * 429 when {@code regenerate=true} is repeated for the same job (job matches) or the same candidate
 * (candidate matches) within the regenerate window.
 */
public class RegenerateRateLimitedException extends ApiException {

    private static final long serialVersionUID = 1L;

    private final int retryAfterSeconds;

    /** The job-scope exception ("Matches for this job ..."). */
    public RegenerateRateLimitedException(int retryAfterSeconds) {
        this(retryAfterSeconds, "job");
    }

    private RegenerateRateLimitedException(int retryAfterSeconds, String subject) {
        super(ErrorCode.REGENERATE_RATE_LIMITED, HttpStatus.TOO_MANY_REQUESTS,
                message(subject, Math.max(1, retryAfterSeconds)));
        this.retryAfterSeconds = Math.max(1, retryAfterSeconds);
    }

    /** {@code GET /api/jobs/{id}/matches}: "Matches for this job were regenerated recently. ..." */
    public static RegenerateRateLimitedException forJob(int retryAfterSeconds) {
        return new RegenerateRateLimitedException(retryAfterSeconds, "job");
    }

    /** {@code GET /api/candidates/{id}/matches}: "Matches for this candidate were regenerated recently. ..." */
    public static RegenerateRateLimitedException forCandidate(int retryAfterSeconds) {
        return new RegenerateRateLimitedException(retryAfterSeconds, "candidate");
    }

    /** Seconds until the job or candidate can be regenerated again (at least 1); sent as Retry-After. */
    public int getRetryAfterSeconds() {
        return retryAfterSeconds;
    }

    private static String message(String subject, int seconds) {
        return "Matches for this " + subject + " were regenerated recently. You can regenerate again in " + seconds
                + (seconds == 1 ? " second" : " seconds")
                + "; reload without regenerate=true to see the current results.";
    }
}
