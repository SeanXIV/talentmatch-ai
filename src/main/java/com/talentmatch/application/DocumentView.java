package com.talentmatch.application;

import com.talentmatch.application.content.DocumentKind;
import com.talentmatch.application.content.DocumentSource;
import com.talentmatch.application.content.Finding;
import com.talentmatch.application.render.ExportFormat;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One version of a tailored CV or cover letter with its content (§5.1 {@code Document}): the
 * {@link DocumentSummaryView} fields plus the content, findings, approval and downloads. PII: the content
 * holds the owner's CV data.
 *
 * @param findingCounts        {@code findings} by severity
 * @param content              a {@code TailoredCvContent} (CV) or a {@code CoverLetterContent} (COVER_LETTER)
 * @param findings             the stored findings, preceded by the computed BLOCKING {@code PROFILE_CHANGED}
 *                             when {@code profileCurrent} is false
 * @param approvalAcknowledged null until approved; then the owner's {@code acknowledgeFindings}
 * @param jobChanged           the job was updated after this version was built (informational)
 * @param files                the exports stored at approval ({@code [PDF, DOCX]}); empty until approved
 * @param downloads            the download URLs (drafts for an unapproved version)
 */
public record DocumentView(
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
        DocumentSummaryView.FindingCounts findingCounts,
        Object content,
        List<Finding> findings,
        Boolean approvalAcknowledged,
        boolean jobChanged,
        List<ExportFormat> files,
        Downloads downloads) {

    /** {@code GET …/documents/{docId}/file} URLs per format. */
    public record Downloads(String pdf, String docx) {

        static Downloads of(UUID applicationId, UUID documentId) {
            String base = "/api/applications/" + applicationId + "/documents/" + documentId + "/file?format=";
            return new Downloads(base + "pdf", base + "docx");
        }
    }
}
