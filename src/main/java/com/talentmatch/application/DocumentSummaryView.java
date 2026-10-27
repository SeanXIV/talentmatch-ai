package com.talentmatch.application;

import com.talentmatch.application.content.DocumentKind;
import com.talentmatch.application.content.DocumentSource;
import java.time.Instant;
import java.util.UUID;

/**
 * One version of a tailored CV or cover letter, without its content (§5.1 {@code DocumentSummary}).
 *
 * @param basedOnId      the version an EDIT was made from (null for AI versions)
 * @param profileVersion the owner profile version the document was built from
 * @param profileCurrent false when the owner profile was saved again since (the version then carries
 *                       the computed BLOCKING finding {@code PROFILE_CHANGED})
 * @param model          the model label (AI versions only)
 * @param approvedAt     null until approved
 * @param findingCounts  stored findings by severity, plus the computed {@code PROFILE_CHANGED}
 */
public record DocumentSummaryView(
        UUID id,
        DocumentKind kind,
        int version,
        DocumentSource source,
        UUID basedOnId,
        int profileVersion,
        boolean profileCurrent,
        String model,
        Instant createdAt,
        Instant approvedAt,
        FindingCounts findingCounts) {

    /** Number of findings by severity. */
    public record FindingCounts(int blocking, int warning, int info) {
    }
}
