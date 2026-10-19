package com.talentmatch.application;

import com.talentmatch.application.content.DocumentKind;
import com.talentmatch.application.content.DocumentSource;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * JDBC access to {@code application_document} (V7; not mapped in JPA).
 *
 * <p>Phase 7 step 1 has the read side the tracker needs (version summaries for the application view,
 * sent-version checks and defaults). Inserting versions, approval and files arrive with steps 5 and 6.
 * Never reads {@code content} here, and never logs findings.
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
