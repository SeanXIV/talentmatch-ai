package com.talentmatch.application;

import com.talentmatch.application.content.DocumentKind;
import com.talentmatch.application.content.DocumentSource;
import com.talentmatch.application.content.Finding;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * JDBC access to {@code application_document} (V7; not mapped in JPA).
 *
 * <p>The read side the tracker needs (version summaries for the application view, sent-version checks
 * and defaults) and the insert of new versions ({@link #insertVersion}, used by generations and, in step 6,
 * by owner edits). Approval and files arrive with step 6. Never logs content or findings.
 */
@Repository
public class DocumentRepository {

    private static final String SUMMARY_COLUMNS = "d.id, d.kind, d.version, d.source, d.based_on_id, "
            + "d.profile_version, d.model, d.created_at, d.approved_at, "
            + "(SELECT count(*) FROM jsonb_array_elements(d.findings) f WHERE f ->> 'severity' = 'BLOCKING') "
            + "AS blocking, "
            + "(SELECT count(*) FROM jsonb_array_elements(d.findings) f WHERE f ->> 'severity' = 'WARNING') "
            + "AS warning, "
            + "(SELECT count(*) FROM jsonb_array_elements(d.findings) f WHERE f ->> 'severity' = 'INFO') AS info";

    private final NamedParameterJdbcTemplate jdbc;

    public DocumentRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** The identifying fields of one version. */
    public record DocumentRef(UUID id, DocumentKind kind, int version, boolean approved) {
    }

    /**
     * A version to insert. V7's {@code ck_application_document_source} pins the combinations: AI versions
     * have a {@code model} and an {@code inputHash} and no base; EDIT versions have a base and neither.
     *
     * @param basedOnId      the edited version (EDIT only; same application and kind)
     * @param profileVersion the owner profile version the content was built from
     * @param jobUpdatedAt   the job's {@code updated_at} the version was built against; null when the job is gone
     * @param content        a {@code TailoredCvContent} (CV) or a {@code CoverLetterContent} (COVER_LETTER)
     * @param findings       grounding findings at creation time (stored, never logged)
     * @param model          the model label (AI only)
     * @param inputHash      sha256 of the prompt (AI only)
     */
    public record NewVersion(UUID applicationId, DocumentKind kind, DocumentSource source, UUID basedOnId,
                             int profileVersion, Instant jobUpdatedAt, Object content, List<Finding> findings,
                             String model, String inputHash) {

        public NewVersion {
            Objects.requireNonNull(applicationId, "applicationId");
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(content, "content");
            findings = findings == null ? List.of() : List.copyOf(findings);
        }

        /** An AI-generated version. */
        public static NewVersion ai(UUID applicationId, DocumentKind kind, int profileVersion, Instant jobUpdatedAt,
                                    Object content, List<Finding> findings, String model, String inputHash) {
            return new NewVersion(applicationId, kind, DocumentSource.AI, null, profileVersion, jobUpdatedAt,
                    content, findings, model, inputHash);
        }
    }

    /**
     * Inserts the next version of the application's documents of that kind ({@code MAX(version) + 1}).
     * Call under the application row lock, which serializes version numbers per application; the unique
     * constraint {@code uq_application_document_version} is the backstop.
     *
     * @return the new version (not approved)
     */
    public DocumentRef insertVersion(NewVersion v) {
        MapSqlParameterSource params = new MapSqlParameterSource("applicationId", v.applicationId())
                .addValue("kind", v.kind().name())
                .addValue("source", v.source().name())
                .addValue("basedOn", v.basedOnId(), Types.OTHER)
                .addValue("profileVersion", v.profileVersion())
                .addValue("jobUpdatedAt", v.jobUpdatedAt() == null ? null : Timestamp.from(v.jobUpdatedAt()),
                        Types.TIMESTAMP)
                .addValue("content", DocumentJson.write(v.content()))
                .addValue("findings", DocumentJson.write(v.findings()))
                .addValue("model", v.model(), Types.VARCHAR)
                .addValue("inputHash", v.inputHash(), Types.VARCHAR);
        return jdbc.queryForObject("INSERT INTO application_document (application_id, kind, version, source, "
                        + "based_on_id, profile_version, job_updated_at, content, findings, model, input_hash) "
                        // Explicit casts: in a SELECT list, untyped parameters (a null uuid, a timestamp) would
                        // resolve to text and fail the insert.
                        + "SELECT CAST(:applicationId AS uuid), :kind, COALESCE(MAX(d.version), 0) + 1, :source, "
                        + "CAST(:basedOn AS uuid), :profileVersion, CAST(:jobUpdatedAt AS timestamptz), "
                        + "CAST(:content AS jsonb), CAST(:findings AS jsonb), :model, :inputHash "
                        + "FROM application_document d "
                        + "WHERE d.application_id = :applicationId AND d.kind = :kind "
                        + "RETURNING id, version",
                params, (rs, n) -> new DocumentRef(rs.getObject("id", UUID.class), v.kind(), rs.getInt("version"),
                        false));
    }

    /** One version of this application (any kind), if it exists. */
    public Optional<DocumentRef> findRef(UUID applicationId, UUID documentId) {
        return jdbc.query("SELECT id, kind, version, approved_at IS NOT NULL AS approved FROM application_document "
                        + "WHERE id = :id AND application_id = :applicationId",
                new MapSqlParameterSource("id", documentId).addValue("applicationId", applicationId),
                (rs, n) -> new DocumentRef(rs.getObject("id", UUID.class), DocumentKind.valueOf(rs.getString("kind")),
                        rs.getInt("version"), rs.getBoolean("approved")))
                .stream().findFirst();
    }

    /** The newest approved version of one kind (the "current" one), if any. */
    public Optional<DocumentRef> newestApproved(UUID applicationId, DocumentKind kind) {
        return jdbc.query("SELECT id, kind, version FROM application_document WHERE application_id = :applicationId "
                        + "AND kind = :kind AND approved_at IS NOT NULL ORDER BY version DESC LIMIT 1",
                new MapSqlParameterSource("applicationId", applicationId).addValue("kind", kind.name()),
                (rs, n) -> new DocumentRef(rs.getObject("id", UUID.class), DocumentKind.valueOf(rs.getString("kind")),
                        rs.getInt("version"), true))
                .stream().findFirst();
    }

    /**
     * Every version of the application, grouped by kind and newest first within a kind.
     *
     * @param currentProfileVersion the owner's current profile version (null when no profile is saved);
     *                              an older {@code profile_version} makes the version not
     *                              {@code profileCurrent} and adds the computed BLOCKING
     *                              {@code PROFILE_CHANGED} to its counts
     */
    public List<DocumentSummaryView> summaries(UUID applicationId, Integer currentProfileVersion) {
        return jdbc.query("SELECT " + SUMMARY_COLUMNS + " FROM application_document d "
                        + "WHERE d.application_id = :applicationId ORDER BY d.kind, d.version DESC",
                new MapSqlParameterSource("applicationId", applicationId), (rs, n) -> {
                    int profileVersion = rs.getInt("profile_version");
                    boolean profileCurrent = currentProfileVersion == null || profileVersion >= currentProfileVersion;
                    return new DocumentSummaryView(
                            rs.getObject("id", UUID.class),
                            DocumentKind.valueOf(rs.getString("kind")),
                            rs.getInt("version"),
                            DocumentSource.valueOf(rs.getString("source")),
                            rs.getObject("based_on_id", UUID.class),
                            profileVersion,
                            profileCurrent,
                            rs.getString("model"),
                            ApplicationRepository.instant(rs, "created_at"),
                            ApplicationRepository.instant(rs, "approved_at"),
                            new DocumentSummaryView.FindingCounts(
                                    rs.getInt("blocking") + (profileCurrent ? 0 : 1),
                                    rs.getInt("warning"),
                                    rs.getInt("info")));
                });
    }
}
