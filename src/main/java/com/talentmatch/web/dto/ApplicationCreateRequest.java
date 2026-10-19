package com.talentmatch.web.dto;

import com.talentmatch.application.ApplicationService;
import java.util.UUID;

/**
 * Body of {@code POST /api/applications}. Unknown fields are rejected by the web layer (400
 * MALFORMED_REQUEST). {@code status} is a string so an unknown or non-initial value is a field error
 * ("A new application starts as NEW or SHORTLISTED.") rather than a parse error.
 *
 * <p>Phase 7 step 1: the tracker fields only. {@code generate}, {@code kinds} and
 * {@code mentionNoticePeriod} arrive with the generation pipeline (step 5).
 *
 * @param jobId  the job to track (required)
 * @param status NEW (default) or SHORTLISTED
 * @param notes  optional, at most 10000 characters
 */
public record ApplicationCreateRequest(UUID jobId, String status, String notes) {

    public ApplicationService.NewApplication toNewApplication() {
        return new ApplicationService.NewApplication(jobId, status, notes);
    }
}
