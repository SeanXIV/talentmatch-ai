package com.talentmatch.service.exception;

import com.talentmatch.web.error.ErrorCode;
import org.springframework.http.HttpStatus;

/**
 * 503 when another request holds a match lock for too long: the job's (job matches) or the
 * candidate's or one of its jobs' (candidate matches).
 */
public class MatchesBusyException extends ApiException {

    private static final long serialVersionUID = 1L;

    /** {@code GET /api/jobs/{id}/matches} and {@code /api/matches/**}. */
    public static final String MESSAGE = "Matches for this job are being recalculated by another request. "
            + "Please try again in a few seconds.";

    /** {@code GET /api/candidates/{id}/matches} and {@code GET /api/profile/matches}. */
    public static final String CANDIDATE_MESSAGE = "Matches for this candidate are being recalculated by another "
            + "request. Please try again in a few seconds.";

    /** Seconds suggested in the Retry-After header. */
    public static final int RETRY_AFTER_SECONDS = 2;

    /** The job-side exception ({@link #MESSAGE}). */
    public MatchesBusyException(Throwable cause) {
        this(MESSAGE, cause);
    }

    private MatchesBusyException(String message, Throwable cause) {
        super(ErrorCode.MATCHES_BUSY, HttpStatus.SERVICE_UNAVAILABLE, message);
        if (cause != null) {
            initCause(cause);
        }
    }

    /** The candidate-side exception ({@link #CANDIDATE_MESSAGE}). */
    public static MatchesBusyException forCandidate(Throwable cause) {
        return new MatchesBusyException(CANDIDATE_MESSAGE, cause);
    }
}
