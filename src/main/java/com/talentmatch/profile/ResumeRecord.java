package com.talentmatch.profile;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** A stored CV without its file bytes. {@code draft} and {@code warnings} are set when SUCCEEDED. */
public record ResumeRecord(
        UUID id,
        String fileName,
        String contentType,
        int sizeBytes,
        String sha256,
        int pageCount,
        ResumeStatus status,
        ExtractionFailure failureReason,
        String extractionModel,
        ProfileDocument draft,
        List<ProfileWarning> warnings,
        Instant uploadedAt,
        Instant extractionStartedAt,
        Instant extractionFinishedAt) {
}
