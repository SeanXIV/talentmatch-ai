package com.talentmatch.web.dto;

import com.talentmatch.profile.ProfileDocument;
import com.talentmatch.profile.ProfileWarning;
import com.talentmatch.profile.ResumeRecord;
import com.talentmatch.profile.ResumeStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * An uploaded CV and its extraction.
 *
 * @param status        PENDING / RUNNING / SUCCEEDED / FAILED
 * @param failureReason why it FAILED (null otherwise)
 * @param message       what to do next, in plain English
 * @param draft         the extracted profile to review (SUCCEEDED only)
 * @param warnings      things to check in the draft (SUCCEEDED only)
 */
public record ResumeResponse(UUID id, String fileName, int sizeBytes, int pageCount, ResumeStatus status,
                             String failureReason, String message, String model, ProfileDocument draft,
                             List<ProfileWarning> warnings, Instant uploadedAt, Instant extractionStartedAt,
                             Instant extractionFinishedAt) {

    public static ResumeResponse of(ResumeRecord r) {
        return new ResumeResponse(r.id(), r.fileName(), r.sizeBytes(), r.pageCount(), r.status(),
                r.failureReason() == null ? null : r.failureReason().name(), message(r), r.extractionModel(),
                r.draft(), r.status() == ResumeStatus.SUCCEEDED ? r.warnings() : null, r.uploadedAt(),
                r.extractionStartedAt(), r.extractionFinishedAt());
    }

    private static String message(ResumeRecord r) {
        return switch (r.status()) {
            case PENDING -> "Your CV is queued to be read. Check back here in a moment.";
            case RUNNING -> "The AI is reading your CV. With a local model this can take several minutes.";
            case SUCCEEDED -> r.warnings().isEmpty()
                    ? "Your draft profile is ready. Review it, then save it with PUT /api/profile."
                    : "Your draft profile is ready, with " + r.warnings().size() + " item(s) to check (see warnings). "
                            + "Review it, then save it with PUT /api/profile.";
            case FAILED -> r.failureReason().hint();
        };
    }
}
