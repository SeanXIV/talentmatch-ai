package com.talentmatch.application;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * One tracked application with its documents and history (§5.1). The job fields are the snapshot
 * taken at creation; they survive the job's deletion ({@code jobId} null, {@code jobExists} false).
 *
 * @param jobClosed          true when the feed job is closed (it can still be tailored)
 * @param jobOrigin          MANUAL or FEED; null when the job was deleted
 * @param allowedTransitions statuses reachable without {@code force}, in table order
 * @param sentCv             the CV version recorded as sent (applied statuses only), or null
 * @param sentCoverLetter    the cover letter version recorded as sent, or null
 * @param latestGeneration   the newest AI generation, or null
 * @param history            status changes, oldest first
 */
public record ApplicationView(
        UUID id,
        UUID jobId,
        String jobTitle,
        String jobCompany,
        String jobUrl,
        boolean jobExists,
        boolean jobClosed,
        String jobOrigin,
        ApplicationStatus status,
        Instant statusChangedAt,
        LocalDate appliedOn,
        String notes,
        List<ApplicationStatus> allowedTransitions,
        SentVersion sentCv,
        SentVersion sentCoverLetter,
        Documents documents,
        GenerationView latestGeneration,
        List<EventView> history,
        Instant createdAt,
        Instant updatedAt) {

    /** A document version recorded as sent. */
    public record SentVersion(UUID id, int version) {
    }

    /** The application's versions per kind. */
    public record Documents(KindDocuments cv, KindDocuments coverLetter) {
    }

    /**
     * @param latest   the newest version, or null when there is none
     * @param approved the newest approved version (the "current" one), or null
     * @param versions the number of versions
     */
    public record KindDocuments(DocumentSummaryView latest, DocumentSummaryView approved, int versions) {
    }
}
