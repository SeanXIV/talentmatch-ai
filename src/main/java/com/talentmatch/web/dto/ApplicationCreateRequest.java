package com.talentmatch.web.dto;

import com.talentmatch.application.ApplicationService;
import java.util.List;
import java.util.UUID;

/**
 * Body of {@code POST /api/applications}. Unknown fields are rejected by the web layer (400
 * MALFORMED_REQUEST). {@code status} and {@code kinds} are strings so an unknown value is a field error
 * (§5.3 messages) rather than a parse error.
 *
 * @param jobId               the job to track (required)
 * @param status              NEW (default) or SHORTLISTED
 * @param notes               optional, at most 10000 characters
 * @param generate            also queue a generation of the documents (default false)
 * @param kinds               CV and/or COVER_LETTER (default both); validated even without {@code generate}
 * @param mentionNoticePeriod add the fixed notice-period sentence to the cover letter (default false)
 */
public record ApplicationCreateRequest(UUID jobId, String status, String notes, Boolean generate, List<String> kinds,
                                       Boolean mentionNoticePeriod) {

    public ApplicationService.NewApplication toNewApplication() {
        return new ApplicationService.NewApplication(jobId, status, notes, generate, kinds, mentionNoticePeriod);
    }
}
