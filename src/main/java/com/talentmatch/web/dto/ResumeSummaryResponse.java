package com.talentmatch.web.dto;

import com.talentmatch.profile.ResumeStatus;
import com.talentmatch.profile.ResumeSummary;
import java.time.Instant;
import java.util.UUID;

/**
 * One uploaded CV in {@code GET /api/profile/resumes}: status and metadata, without the draft.
 *
 * @param failureReason why it FAILED (null otherwise)
 * @param warningCount  number of draft warnings when SUCCEEDED (null otherwise)
 */
public record ResumeSummaryResponse(UUID id, String fileName, int sizeBytes, int pageCount, ResumeStatus status,
                                    String failureReason, String model, Integer warningCount, Instant uploadedAt,
                                    Instant extractionStartedAt, Instant extractionFinishedAt) {

    public static ResumeSummaryResponse of(ResumeSummary r) {
        return new ResumeSummaryResponse(r.id(), r.fileName(), r.sizeBytes(), r.pageCount(), r.status(),
                r.failureReason() == null ? null : r.failureReason().name(), r.extractionModel(), r.warningCount(),
                r.uploadedAt(), r.extractionStartedAt(), r.extractionFinishedAt());
    }
}
