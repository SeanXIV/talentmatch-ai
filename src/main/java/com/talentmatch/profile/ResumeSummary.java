package com.talentmatch.profile;

import java.time.Instant;
import java.util.UUID;

/**
 * An uploaded CV for listing: status and metadata only (no file, text or draft).
 *
 * @param warningCount number of draft warnings when SUCCEEDED, otherwise null
 */
public record ResumeSummary(
        UUID id,
        String fileName,
        int sizeBytes,
        int pageCount,
        ResumeStatus status,
        ExtractionFailure failureReason,
        String extractionModel,
        Integer warningCount,
        Instant uploadedAt,
        Instant extractionStartedAt,
        Instant extractionFinishedAt) {
}
