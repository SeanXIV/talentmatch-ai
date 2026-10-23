package com.talentmatch.application;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * JDBC access to {@code application} and {@code application_event} (V7; not mapped in JPA). Reads join
 * the live job (origin, closed flag) when it still exists; the title, company and URL always come from
 * the application's own snapshot.
 *
 * <p>PII: {@code notes} is the owner's free text. It is read and written here, never logged.
 */
@Repository
public class ApplicationRepository {

    /** Longest wait for the application row lock before the request fails with 503 SERVICE_BUSY. */
    static final String LOCK_TIMEOUT = "10s";

    private static final String SQL_SET_LOCK_TIMEOUT = "SET LOCAL lock_timeout = '" + LOCK_TIMEOUT + "'";

    /** Longest wait of the generation worker for the application row lock (see {@link #lockForWorker}). */
    static final String WORKER_LOCK_TIMEOUT = "120s";

    private static final String SQL_SET_WORKER_LOCK_TIMEOUT =
            "SET LOCAL lock_timeout = '" + WORKER_LOCK_TIMEOUT + "'";

    private static final String COLUMNS = "a.id, a.job_id, a.job_title, a.job_company, a.job_url, "
            + "j.origin AS job_origin, (f.closed_at IS NOT NULL) AS job_closed, a.status, a.status_changed_at, "
            + "a.applied_on, a.notes, a.sent_cv_id, a.sent_cover_letter_id, a.created_at, a.updated_at";

    private static final String FROM = " FROM application a LEFT JOIN job j ON j.id = a.job_id "
            + "LEFT JOIN feed_job f ON f.job_id = a.job_id";

    private static final String SUMMARY_COLUMNS = "a.id, a.job_id, a.job_title, a.job_company, a.job_url, a.status, "
            + "a.applied_on, a.updated_at, "
            + "(SELECT max(d.version) FROM application_document d "
            + "WHERE d.application_id = a.id AND d.kind = 'CV') AS latest_cv, "
            + "(SELECT max(d.version) FROM application_document d "
            + "WHERE d.application_id = a.id AND d.kind = 'COVER_LETTER') AS latest_cover_letter, "
            + "EXISTS (SELECT 1 FROM application_document d WHERE d.application_id = a.id AND d.kind = 'CV' "
            + "AND d.approved_at IS NOT NULL) AS approved_cv, "
            + "EXISTS (SELECT 1 FROM application_document d WHERE d.application_id = a.id AND d.kind = 'COVER_LETTER' "
            + "AND d.approved_at IS NOT NULL) AS approved_cover_letter, "
            + "(SELECT g.status FROM application_generation g WHERE g.application_id = a.id "
            + "ORDER BY g.queued_at DESC, g.id DESC LIMIT 1) AS generation_status";

    private static final RowMapper<ApplicationRow> ROW = (rs, n) -> new ApplicationRow(
            rs.getObject("id", UUID.class),
            rs.getObject("job_id", UUID.class),
            rs.getString("job_title"),
            rs.getString("job_company"),
            rs.getString("job_url"),
            rs.getString("job_origin"),
            rs.getBoolean("job_closed"),
            ApplicationStatus.valueOf(rs.getString("status")),
            instant(rs, "status_changed_at"),
            rs.getObject("applied_on", LocalDate.class),
            rs.getString("notes"),
            rs.getObject("sent_cv_id", UUID.class),
            rs.getObject("sent_cover_letter_id", UUID.class),
            instant(rs, "created_at"),
            instant(rs, "updated_at"));

    private static final RowMapper<ApplicationSummaryView> SUMMARY = (rs, n) -> new ApplicationSummaryView(
            rs.getObject("id", UUID.class),
            rs.getObject("job_id", UUID.class),
            rs.getString("job_title"),
            rs.getString("job_company"),
            rs.getString("job_url"),
            rs.getObject("job_id") != null,
            ApplicationStatus.valueOf(rs.getString("status")),
            rs.getObject("applied_on", LocalDate.class),
            (Integer) rs.getObject("latest_cv"),
            (Integer) rs.getObject("latest_cover_letter"),
            rs.getBoolean("approved_cv"),
            rs.getBoolean("approved_cover_letter"),
            rs.getString("generation_status"),
            instant(rs, "updated_at"));

    private static final RowMapper<EventView> EVENT = (rs, n) -> new EventView(
            status(rs.getString("from_status")),
            ApplicationStatus.valueOf(rs.getString("to_status")),
            rs.getBoolean("forced"),
            instant(rs, "at"));

    private final NamedParameterJdbcTemplate jdbc;

    public ApplicationRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * An application row with the live job's origin and closed flag.
     *
     * @param jobOrigin null when the job was deleted
     * @param jobClosed true when the feed job is closed (false for manual or deleted jobs)
     */
    public record ApplicationRow(UUID id, UUID jobId, String jobTitle, String jobCompany, String jobUrl,
                                 String jobOrigin, boolean jobClosed, ApplicationStatus status,
                                 Instant statusChangedAt, LocalDate appliedOn, String notes, UUID sentCvId,
                                 UUID sentCoverLetterId, Instant createdAt, Instant updatedAt) {
    }

    /** What an application copies from its job when it is created. @param url the feed job's URL, or null */
    public record JobSnapshot(UUID jobId, String title, String company, String url) {
    }

    // ------------------------------------------------------------------ read

    public Optional<ApplicationRow> find(UUID id) {
        return jdbc.query("SELECT " + COLUMNS + FROM + " WHERE a.id = :id", new MapSqlParameterSource("id", id), ROW)
                .stream().findFirst();
    }

    /**
     * Locks the application row until the transaction ends, waiting at most {@value #LOCK_TIMEOUT}
     * ({@code SET LOCAL lock_timeout}; a timeout fails with SQLState 55P03, which the web layer turns
     * into 503 SERVICE_BUSY). Must run inside a transaction.
     */
    public Optional<ApplicationRow> lockForUpdate(UUID id) {
        jdbc.getJdbcOperations().execute(SQL_SET_LOCK_TIMEOUT);
        try {
            return jdbc.query("SELECT " + COLUMNS + FROM + " WHERE a.id = :id FOR UPDATE OF a",
                    new MapSqlParameterSource("id", id), ROW).stream().findFirst();
        } catch (PessimisticLockingFailureException e) {
            throw e;
        } catch (DataAccessException e) {
            // 55P03 (lock_not_available) isn't translated to a locking exception here: map it so the
            // web layer answers 503 SERVICE_BUSY.
            if ("55P03".equals(sqlState(e))) {
                throw new CannotAcquireLockException("Timed out waiting for the lock of application " + id, e);
            }
            throw e;
        }
    }

    /**
     * Locks the application row for a background writer (the generation worker storing a version) until
     * the transaction ends. Waits up to {@value #WORKER_LOCK_TIMEOUT}, longer than a request would: request
     * transactions hold the row briefly, and giving up would throw away minutes of model work. Must run
     * inside a transaction.
     *
     * @return false when the application no longer exists (deleted meanwhile)
     */
    public boolean lockForWorker(UUID id) {
        jdbc.getJdbcOperations().execute(SQL_SET_WORKER_LOCK_TIMEOUT);
        return !jdbc.queryForList("SELECT id FROM application WHERE id = :id FOR UPDATE",
                new MapSqlParameterSource("id", id), UUID.class).isEmpty();
    }

    private static String sqlState(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause() == c ? null : c.getCause()) {
            if (c instanceof SQLException sql && sql.getSQLState() != null) {
                return sql.getSQLState();
            }
        }
        return null;
    }

    /** The application tracking this job, if any. */
    public Optional<UUID> findIdByJob(UUID jobId) {
        return jdbc.queryForList("SELECT id FROM application WHERE job_id = :jobId",
                new MapSqlParameterSource("jobId", jobId), UUID.class).stream().findFirst();
    }

    /** Status history, oldest first. */
    public List<EventView> events(UUID applicationId) {
        return jdbc.query("SELECT from_status, to_status, forced, at FROM application_event "
                + "WHERE application_id = :id ORDER BY at, id", new MapSqlParameterSource("id", applicationId), EVENT);
    }

    /** @param statuses empty = every status */
    public long count(Set<ApplicationStatus> statuses) {
        Long count = jdbc.queryForObject("SELECT count(*) FROM application a" + where(statuses),
                filterParams(statuses), Long.class);
        return count == null ? 0 : count;
    }

    /** One page of list rows. @param statuses empty = every status */
    public List<ApplicationSummaryView> findPage(Set<ApplicationStatus> statuses, ApplicationSort order, long offset,
                                                 int limit) {
        MapSqlParameterSource params = filterParams(statuses).addValue("offset", offset).addValue("limit", limit);
        return jdbc.query("SELECT " + SUMMARY_COLUMNS + " FROM application a" + where(statuses)
                + " ORDER BY " + order.sql + " LIMIT :limit OFFSET :offset", params, SUMMARY);
    }

    // ------------------------------------------------------------------ write

    /**
     * The job's title, company and (feed jobs only) primary URL. Takes a {@code FOR KEY SHARE} lock on
     * the job row, so the job can't be deleted before the application referencing it commits.
     */
    public Optional<JobSnapshot> findJobForSnapshot(UUID jobId) {
        return jdbc.query("SELECT j.id, j.title, j.company, f.primary_url FROM job j "
                        + "LEFT JOIN feed_job f ON f.job_id = j.id WHERE j.id = :id FOR KEY SHARE OF j",
                new MapSqlParameterSource("id", jobId), (rs, n) -> new JobSnapshot(rs.getObject("id", UUID.class),
                        rs.getString("title"), rs.getString("company"), rs.getString("primary_url")))
                .stream().findFirst();
    }

    /**
     * Inserts a new application with the job snapshot.
     *
     * @return the new id
     * @throws org.springframework.dao.DuplicateKeyException when the job is already tracked
     *         ({@code uq_application_job})
     */
    public UUID insert(JobSnapshot job, ApplicationStatus status, String notes) {
        MapSqlParameterSource params = new MapSqlParameterSource("jobId", job.jobId())
                .addValue("title", job.title())
                .addValue("company", job.company())
                .addValue("url", job.url(), Types.VARCHAR)
                .addValue("status", status.name())
                .addValue("notes", notes, Types.VARCHAR);
        return jdbc.queryForObject("INSERT INTO application (job_id, job_title, job_company, job_url, status, notes) "
                + "VALUES (:jobId, :title, :company, :url, :status, :notes) RETURNING id", params, UUID.class);
    }

    /** Appends a history event. @param from null for the creation event */
    public void insertEvent(UUID applicationId, ApplicationStatus from, ApplicationStatus to, boolean forced) {
        MapSqlParameterSource params = new MapSqlParameterSource("id", applicationId)
                .addValue("from", from == null ? null : from.name(), Types.VARCHAR)
                .addValue("to", to.name())
                .addValue("forced", forced);
        jdbc.update("INSERT INTO application_event (application_id, from_status, to_status, forced) "
                + "VALUES (:id, :from, :to, :forced)", params);
    }

    /**
     * Replaces the tracker fields. {@code status_changed_at} moves to now() only when
     * {@code statusChanged}; {@code updated_at} is set by the trigger.
     *
     * @return rows updated
     */
    public int update(UUID id, ApplicationStatus status, boolean statusChanged, LocalDate appliedOn, String notes,
                      UUID sentCvId, UUID sentCoverLetterId) {
        MapSqlParameterSource params = new MapSqlParameterSource("id", id)
                .addValue("status", status.name())
                .addValue("appliedOn", appliedOn, Types.DATE)
                .addValue("notes", notes, Types.VARCHAR)
                .addValue("sentCv", sentCvId, Types.OTHER)
                .addValue("sentCoverLetter", sentCoverLetterId, Types.OTHER);
        return jdbc.update("UPDATE application SET status = :status, applied_on = :appliedOn, notes = :notes, "
                + "sent_cv_id = :sentCv, sent_cover_letter_id = :sentCoverLetter"
                + (statusChanged ? ", status_changed_at = now()" : "") + " WHERE id = :id", params);
    }

    /**
     * Deletes the application. Documents, files, generations and events cascade in the same statement
     * (V7; the append-only triggers allow the cascade because the parent row is already gone).
     *
     * @return rows deleted
     */
    public int delete(UUID id) {
        return jdbc.update("DELETE FROM application WHERE id = :id", new MapSqlParameterSource("id", id));
    }

    // ------------------------------------------------------------------ helpers

    private static String where(Set<ApplicationStatus> statuses) {
        return statuses == null || statuses.isEmpty() ? "" : " WHERE a.status IN (:statuses)";
    }

    private static MapSqlParameterSource filterParams(Set<ApplicationStatus> statuses) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        if (statuses != null && !statuses.isEmpty()) {
            params.addValue("statuses", statuses.stream().map(Enum::name).toList());
        }
        return params;
    }

    private static ApplicationStatus status(String value) {
        return value == null ? null : ApplicationStatus.valueOf(value);
    }

    static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp ts = rs.getTimestamp(column);
        return ts == null ? null : ts.toInstant();
    }
}
