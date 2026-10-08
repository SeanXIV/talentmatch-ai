package com.talentmatch.service.exception;

import com.talentmatch.web.error.ErrorCode;
import java.util.UUID;
import org.springframework.http.HttpStatus;

/** 404 for a missing resource. */
public class NotFoundException extends ApiException {

    private static final long serialVersionUID = 1L;

    public NotFoundException(ErrorCode code, String message) {
        super(code, HttpStatus.NOT_FOUND, message);
    }

    public static NotFoundException candidate(UUID id) {
        return new NotFoundException(ErrorCode.CANDIDATE_NOT_FOUND, "No candidate with id " + id + ".");
    }

    public static NotFoundException job(UUID id) {
        return new NotFoundException(ErrorCode.JOB_NOT_FOUND, "No job with id " + id + ".");
    }

    public static NotFoundException skill(UUID id) {
        return new NotFoundException(ErrorCode.SKILL_NOT_FOUND, "No skill with id " + id + ".");
    }

    public static NotFoundException resume(UUID id) {
        return new NotFoundException(ErrorCode.RESUME_NOT_FOUND, "No uploaded CV with id " + id + ".");
    }

    public static NotFoundException profile() {
        return new NotFoundException(ErrorCode.PROFILE_NOT_FOUND, "You have not saved a profile yet. Upload your CV "
                + "with POST /api/profile/resume, review the draft, then save it with PUT /api/profile.");
    }

    public static NotFoundException preferences() {
        return new NotFoundException(ErrorCode.PREFERENCES_NOT_FOUND,
                "No job preferences saved yet; the feed is not filtered. Save them with PUT /api/preferences.");
    }

    public static NotFoundException skillAlias(UUID skillId, UUID aliasId) {
        return new NotFoundException(ErrorCode.SKILL_ALIAS_NOT_FOUND,
                "Skill " + skillId + " has no alias with id " + aliasId + ".");
    }

    public static NotFoundException recomputeRun(UUID id, int historySize) {
        return new NotFoundException(ErrorCode.RECOMPUTE_RUN_NOT_FOUND, "No recompute run with id " + id
                + ". Run status is kept in memory (last " + historySize
                + " runs) and is lost when the server restarts.");
    }
}
