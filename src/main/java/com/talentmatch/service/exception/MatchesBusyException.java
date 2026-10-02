package com.talentmatch.service.exception;

import com.talentmatch.web.error.ErrorCode;
import org.springframework.http.HttpStatus;

/** 503 when another request holds the job's match lock for too long. */
public class MatchesBusyException extends ApiException {

    private static final long serialVersionUID = 1L;

    public static final String MESSAGE = "Matches for this job are being recalculated by another request. "
            + "Please try again in a few seconds.";

    /** Seconds suggested in the Retry-After header. */
    public static final int RETRY_AFTER_SECONDS = 2;

    public MatchesBusyException(Throwable cause) {
        super(ErrorCode.MATCHES_BUSY, HttpStatus.SERVICE_UNAVAILABLE, MESSAGE);
        if (cause != null) {
            initCause(cause);
        }
    }
}
