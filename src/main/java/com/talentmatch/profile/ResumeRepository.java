package com.talentmatch.profile;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * JDBC access to the {@code resume} table (V4). Status changes are guarded UPDATEs, so two
 * workers (or a retry racing a running extraction) can never both own one CV.
 */
@Repository
public class ResumeRepository {

    private static final String COLUMNS = "id, file_name, content_type, size_bytes, sha256, page_count, status, "
            + "failure_reason, extraction_model, draft::text AS draft, warnings::text AS warnings, uploaded_at, "
            + "extraction_started_at, extraction_finished_at";

    /** Extraction claims allowed before a CV is FAILED/TOO_MANY_ATTEMPTS (see V4 resume.attempts). */
    public static final int MAX_ATTEMPTS = 3;

    private static final RowMapper<ResumeRecord> ROW = ResumeRepository::map;

    private final NamedParameterJdbcTemplate jdbc;

    public ResumeRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public UUID insert(String fileName, String contentType, byte[] content, String sha256, int pageCount,
                       String extractedText) {
        MapSqlParameterSource p = new MapSqlParameterSource()
                .addValue("fileName", fileName)
                .addValue("contentType", contentType)
                .addValue("size", content.length)
                .addValue("sha", sha256)
                .addValue("content", content, Types.BINARY)
                .addValue("pages", pageCount)
                .addValue("text", extractedText);
        return jdbc.queryForObject("INSERT INTO resume (file_name, content_type, size_bytes, sha256, content, "
                + "page_count, extracted_text) VALUES (:fileName, :contentType, :size, :sha, :content, :pages, :text) "
                + "RETURNING id", p, UUID.class);
    }

    public Optional<ResumeRecord> find(UUID id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM resume WHERE id = :id",
                new MapSqlParameterSource("id", id), ROW).stream().findFirst();
    }

    /** The newest upload of the same file that is queued, running or done (not FAILED). */
    public Optional<ResumeRecord> findReusable(String sha256) {
        return jdbc.query("SELECT " + COLUMNS + " FROM resume WHERE sha256 = :sha AND status <> 'FAILED' "
                + "ORDER BY uploaded_at DESC LIMIT 1", new MapSqlParameterSource("sha", sha256), ROW)
                .stream().findFirst();
    }

    public Optional<StoredFile> findFile(UUID id) {
        return jdbc.query("SELECT file_name, content_type, content FROM resume WHERE id = :id",
                new MapSqlParameterSource("id", id),
                (rs, n) -> new StoredFile(rs.getString(1), rs.getString(2), rs.getBytes(3))).stream().findFirst();
    }

    public Optional<String> findText(UUID id) {
        return jdbc.queryForList("SELECT extracted_text FROM resume WHERE id = :id",
                new MapSqlParameterSource("id", id), String.class).stream().findFirst();
    }

    /**
     * PENDING → RUNNING, counting the attempt. False if another worker got it first, it is no
     * longer pending, or it has used up {@link #MAX_ATTEMPTS} (see {@link #failExhausted}).
     */
    public boolean claim(UUID id, Instant now) {
        return jdbc.update("UPDATE resume SET status = 'RUNNING', attempts = attempts + 1, extraction_started_at = :now, "
                + "extraction_finished_at = NULL WHERE id = :id AND status = 'PENDING' AND attempts < :max",
                new MapSqlParameterSource("id", id).addValue("now", Timestamp.from(now))
                        .addValue("max", MAX_ATTEMPTS)) == 1;
    }

    /** PENDING with no attempts left → FAILED/TOO_MANY_ATTEMPTS. True if it changed the row. */
    public boolean failExhausted(UUID id, Instant now) {
        return jdbc.update("UPDATE resume SET status = 'FAILED', failure_reason = :reason, extraction_finished_at = :now "
                + "WHERE id = :id AND status = 'PENDING' AND attempts >= :max",
                new MapSqlParameterSource("id", id).addValue("reason", ExtractionFailure.TOO_MANY_ATTEMPTS.name())
                        .addValue("now", Timestamp.from(now)).addValue("max", MAX_ATTEMPTS)) == 1;
    }

    public boolean succeed(UUID id, String model, String draftJson, String warningsJson, Instant now) {
        return jdbc.update("UPDATE resume SET status = 'SUCCEEDED', failure_reason = NULL, extraction_model = :model, "
                + "draft = CAST(:draft AS jsonb), warnings = CAST(:warnings AS jsonb), extraction_finished_at = :now "
                + "WHERE id = :id AND status = 'RUNNING'",
                new MapSqlParameterSource("id", id).addValue("model", model).addValue("draft", draftJson)
                        .addValue("warnings", warningsJson).addValue("now", Timestamp.from(now))) == 1;
    }

    /** RUNNING or PENDING → FAILED (PENDING when the queue rejects it or AI is off). */
    public boolean fail(UUID id, ExtractionFailure reason, String model, Instant now) {
        return jdbc.update("UPDATE resume SET status = 'FAILED', failure_reason = :reason, extraction_model = :model, "
                + "draft = NULL, warnings = NULL, extraction_finished_at = :now "
                + "WHERE id = :id AND status IN ('PENDING', 'RUNNING')",
                new MapSqlParameterSource("id", id).addValue("reason", reason.name()).addValue("model", model)
                        .addValue("now", Timestamp.from(now))) == 1;
    }

    /** FAILED or SUCCEEDED → PENDING for a re-run (attempts start again). False while PENDING/RUNNING. */
    public boolean requeue(UUID id) {
        return jdbc.update("UPDATE resume SET status = 'PENDING', failure_reason = NULL, draft = NULL, warnings = NULL, "
                + "attempts = 0, extraction_started_at = NULL, extraction_finished_at = NULL "
                + "WHERE id = :id AND status IN ('FAILED', 'SUCCEEDED')", new MapSqlParameterSource("id", id)) == 1;
    }

    /** After a restart: RUNNING work was lost, so put it back in the queue. */
    public int resetRunning() {
        return jdbc.update("UPDATE resume SET status = 'PENDING', extraction_started_at = NULL WHERE status = 'RUNNING'",
                new MapSqlParameterSource());
    }

    public List<UUID> findPendingIds() {
        return jdbc.queryForList("SELECT id FROM resume WHERE status = 'PENDING' ORDER BY uploaded_at",
                new MapSqlParameterSource(), UUID.class);
    }

    /** Every uploaded CV, newest first, without file, text or draft. */
    public List<ResumeSummary> list() {
        return jdbc.query("SELECT id, file_name, size_bytes, page_count, status, failure_reason, extraction_model, "
                + "CASE WHEN warnings IS NULL THEN NULL ELSE jsonb_array_length(warnings) END AS warning_count, "
                + "uploaded_at, extraction_started_at, extraction_finished_at "
                + "FROM resume ORDER BY uploaded_at DESC, id", new MapSqlParameterSource(), (rs, n) -> {
                    String reason = rs.getString("failure_reason");
                    Object warningCount = rs.getObject("warning_count");
                    return new ResumeSummary(rs.getObject("id", UUID.class), rs.getString("file_name"),
                            rs.getInt("size_bytes"), rs.getInt("page_count"),
                            ResumeStatus.valueOf(rs.getString("status")),
                            reason == null ? null : ExtractionFailure.valueOf(reason), rs.getString("extraction_model"),
                            warningCount == null ? null : ((Number) warningCount).intValue(),
                            instant(rs, "uploaded_at"), instant(rs, "extraction_started_at"),
                            instant(rs, "extraction_finished_at"));
                });
    }

    /**
     * Deletes a CV unless the model is reading it. A PENDING CV is deleted too (the worker's
     * claim then finds nothing). {@code owner_profile.resume_id} becomes NULL (ON DELETE SET NULL).
     *
     * @return true if deleted; false if it does not exist or is RUNNING
     */
    public boolean deleteUnlessRunning(UUID id) {
        return jdbc.update("DELETE FROM resume WHERE id = :id AND status <> 'RUNNING'",
                new MapSqlParameterSource("id", id)) == 1;
    }

    /**
     * Serializes uploads of the same file until the surrounding transaction ends, so two
     * simultaneous uploads of one CV create one row (N1). Must run inside a transaction.
     */
    public void lockSha(String sha256) {
        jdbc.query("SELECT pg_advisory_xact_lock(hashtext(:key))",
                new MapSqlParameterSource("key", "talentmatch.resume:" + sha256), rs -> null);
    }

    /** Original file for download. */
    public record StoredFile(String fileName, String contentType, byte[] content) {
    }

    private static ResumeRecord map(ResultSet rs, int rowNum) throws SQLException {
        String reason = rs.getString("failure_reason");
        return new ResumeRecord(
                rs.getObject("id", UUID.class),
                rs.getString("file_name"),
                rs.getString("content_type"),
                rs.getInt("size_bytes"),
                rs.getString("sha256"),
                rs.getInt("page_count"),
                ResumeStatus.valueOf(rs.getString("status")),
                reason == null ? null : ExtractionFailure.valueOf(reason),
                rs.getString("extraction_model"),
                ProfileJson.document(rs.getString("draft")),
                ProfileJson.warnings(rs.getString("warnings")),
                instant(rs, "uploaded_at"),
                instant(rs, "extraction_started_at"),
                instant(rs, "extraction_finished_at"));
    }

    static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp ts = rs.getTimestamp(column);
        return ts == null ? null : ts.toInstant();
    }
}
