package com.talentmatch.application;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One row of {@code GET /api/applications} (§5.1).
 *
 * @param latestCvVersion          the newest CV version number, or null when there is none
 * @param latestCoverLetterVersion the newest cover letter version number, or null
 * @param approvedCv               true when at least one CV version is approved
 * @param approvedCoverLetter      true when at least one cover letter version is approved
 * @param generationStatus         the newest generation's status, or null when there is none
 */
public record ApplicationSummaryView(
        UUID id,
        UUID jobId,
        String jobTitle,
        String jobCompany,
        String jobUrl,
        boolean jobExists,
        ApplicationStatus status,
        LocalDate appliedOn,
        Integer latestCvVersion,
        Integer latestCoverLetterVersion,
        boolean approvedCv,
        boolean approvedCoverLetter,
        String generationStatus,
        Instant updatedAt) {
}
