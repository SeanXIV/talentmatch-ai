package com.talentmatch.service.exception;

import com.talentmatch.web.error.ErrorCode;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.HttpStatus;

/** 409 when a batch recompute is already running (only one at a time). */
public class RecomputeAlreadyRunningException extends ApiException {

    private static final long serialVersionUID = 1L;

    private final UUID activeRunId;

    /**
     * @param activeRunId id of the running run, or null if it is just finishing
     * @param startedAt   when the running run started, or null
     */
    public RecomputeAlreadyRunningException(UUID activeRunId, Instant startedAt) {
        super(ErrorCode.RECOMPUTE_ALREADY_RUNNING, HttpStatus.CONFLICT, message(activeRunId, startedAt));
        this.activeRunId = activeRunId;
    }

    /** Id of the active run (for the Location header), or null. */
    public UUID getActiveRunId() {
        return activeRunId;
    }

    private static String message(UUID activeRunId, Instant startedAt) {
        if (activeRunId == null) {
            return "A recompute is just finishing. Please try again in a moment.";
        }
        return "A recompute is already running (started " + startedAt + "). Track it at "
                + "/api/matches/recompute/" + activeRunId + ".";
    }
}
