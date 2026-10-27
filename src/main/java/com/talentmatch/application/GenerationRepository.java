package com.talentmatch.application;

import com.talentmatch.application.content.DocumentKind;
import com.talentmatch.application.tailoring.GenerationFailure;
import com.talentmatch.application.tailoring.JobFacts;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * JDBC access to {@code application_generation} (V7; not mapped in JPA), plus the job read a generation
 * tailors to. Status changes are guarded UPDATEs (the Phase 4 pattern), so two workers, or a worker and
 * recovery, can never both own one generation. Every write after the claim requires {@code RUNNING}: once
 * the application is deleted (the row cascades) or the row was failed elsewhere, they update nothing.
 *
 * <p>Never reads or logs document content; the job description is read for the prompt only.
 */
@Repository
public class GenerationRepository {

    /** Claims per generation before it is FAILED/TOO_MANY_ATTEMPTS (§4.6). */
    public static final int MAX_ATTEMPTS = GenerationFailure.MAX_ATTEMPTS;

    private static final String COLUMNS = "id, application_id, want_cv, want_cover_letter, mention_notice_period, "
            + "status, failure_reason, attempts, model, profile_version, cv_document_id, cv_failure, "
            + "cover_letter_document_id, cover_letter_failure, queued_at, started_at, finished_at";

    private static final RowMapper<GenerationRow> ROW = (rs, n) -> new GenerationRow(
            rs.getObject("id", UUID.class),
            rs.getObject("application_id", UUID.class),
            rs.getBoolean("want_cv"),
            rs.getBoolean("want_cover_letter"),
            rs.getBoolean("mention_notice_period"),
            rs.getString("status"),
            failure(rs.getString("failure_reason")),
            rs.getInt("attempts"),
            rs.getString("model"),
            (Integer) rs.getObject("profile_version"),
            rs.getObject("cv_document_id", UUID.class),
            failure(rs.getString("cv_failure")),
            rs.getObject("cover_letter_document_id", UUID.class),
            failure(rs.getString("cover_letter_failure")),
            ApplicationRepository.instant(rs, "queued_at"),
            ApplicationRepository.instant(rs, "started_at"),
            ApplicationRepository.instant(rs, "finished_at"));

    private final NamedParameterJdbcTemplate jdbc;

    public GenerationRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * One generation row.
     *
     * @param status         PENDING, RUNNING, SUCCEEDED or FAILED
     * @param failureReason  the generation's failure (FAILED only)
     * @param model          the tailoring model label, once known
     * @param profileVersion the owner profile version the run read, once started
     */
    public record GenerationRow(UUID id, UUID applicationId, boolean wantCv, boolean wantCoverLetter,
                                boolean mentionNoticePeriod, String status, GenerationFailure failureReason,
                                int attempts, String model, Integer profileVersion, UUID cvDocumentId,
                                GenerationFailure cvFailure, UUID coverLetterDocumentId,
                                GenerationFailure coverLetterFailure, Instant queuedAt, Instant startedAt,
                                Instant finishedAt) {

        public boolean wants(DocumentKind kind) {
            return kind == DocumentKind.CV ? wantCv : wantCoverLetter;
        }

        /** The version stored for this kind, or null. */
        public UUID documentId(DocumentKind kind) {
            return kind == DocumentKind.CV ? cvDocumentId : coverLetterDocumentId;
        }

        /** This kind's failure, or null. */
        public GenerationFailure failure(DocumentKind kind) {
            return kind == DocumentKind.CV ? cvFailure : coverLetterFailure;
        }

        /** The API view (§5.1 {@code Generation}); {@code results} lists the wanted kinds that finished. */
        public GenerationView view() {
            List<GenerationView.Result> results = new ArrayList<>(2);
            for (DocumentKind kind : DocumentKind.values()) {
                UUID document = documentId(kind);
                GenerationFailure failure = failure(kind);
                if (wants(kind) && (document != null || failure != null)) {
                    results.add(new GenerationView.Result(kind, document, failure == null ? null : failure.name(),
                            failure == null ? null : failure.hint()));
                }
            }
            return new GenerationView(id, applicationId, status, wantCv, wantCoverLetter,
                    failureReason == null ? null : failureReason.name(),
                    failureReason == null ? null : failureReason.hint(), model, profileVersion, attempts, queuedAt,
                    startedAt, finishedAt, List.copyOf(results));
        }
    }

    /**
     * The job as a generation reads it.
     *
     * @param updatedAt {@code job.updated_at}, stored on each version ({@code job_updated_at})
     */
    public record JobRow(JobFacts facts, Instant updatedAt) {
    }

    /** A PENDING or RUNNING generation of an application. */
    public record ActiveGeneration(UUID id, String status) {
    }

    // ------------------------------------------------------------------ read

    public Optional<GenerationRow> find(UUID id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM application_generation WHERE id = :id",
                new MapSqlParameterSource("id", id), ROW).stream().findFirst();
    }

    /** One generation of this application, if it exists. */
    public Optional<GenerationRow> find(UUID applicationId, UUID id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM application_generation WHERE id = :id "
                        + "AND application_id = :applicationId",
                new MapSqlParameterSource("id", id).addValue("applicationId", applicationId), ROW)
                .stream().findFirst();
    }

    /** The application's newest generation (any status), if any. */
    public Optional<GenerationRow> latestFor(UUID applicationId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM application_generation WHERE application_id = :applicationId "
                        + "ORDER BY queued_at DESC, id DESC LIMIT 1",
                new MapSqlParameterSource("applicationId", applicationId), ROW).stream().findFirst();
    }

    /** The application's PENDING or RUNNING generation, if any (at most one: uq_application_generation_active). */
    public Optional<ActiveGeneration> findActive(UUID applicationId) {
        return jdbc.query("SELECT id, status FROM application_generation WHERE application_id = :applicationId "
                        + "AND status IN ('PENDING', 'RUNNING')",
                new MapSqlParameterSource("applicationId", applicationId),
                (rs, n) -> new ActiveGeneration(rs.getObject("id", UUID.class), rs.getString("status")))
                .stream().findFirst();
    }

    public List<UUID> findPendingIds() {
        return jdbc.queryForList("SELECT id FROM application_generation WHERE status = 'PENDING' ORDER BY queued_at, id",
                new MapSqlParameterSource(), UUID.class);
    }

    /**
     * The job's title, company, description and skills (required and nice-to-have names, in name order),
     * or empty when the job no longer exists.
     */
    public Optional<JobRow> findJob(UUID jobId) {
        MapSqlParameterSource params = new MapSqlParameterSource("id", jobId);
        Optional<JobRow> job = jdbc.query("SELECT title, company, description, updated_at FROM job WHERE id = :id",
                params, (rs, n) -> new JobRow(new JobFacts(rs.getString("title"), rs.getString("company"),
                        rs.getString("description"), List.of(), List.of()), ApplicationRepository.instant(rs,
                        "updated_at"))).stream().findFirst();
        if (job.isEmpty()) {
            return job;
        }
        List<String> required = new ArrayList<>();
        List<String> nice = new ArrayList<>();
        jdbc.query("SELECT s.name, js.required FROM job_skill js JOIN skill s ON s.id = js.skill_id "
                + "WHERE js.job_id = :id ORDER BY lower(s.name), s.id", params, rs -> {
                    (rs.getBoolean("required") ? required : nice).add(rs.getString("name"));
                });
        JobFacts f = job.get().facts();
        return Optional.of(new JobRow(new JobFacts(f.title(), f.company(), f.description(), required, nice),
                job.get().updatedAt()));
    }

    // ------------------------------------------------------------------ write

    /**
     * Queues a generation (PENDING). Call under the application row lock, after {@link #findActive}.
     *
     * @return the new id
     * @throws org.springframework.dao.DuplicateKeyException when the application already has an active
     *         generation ({@code uq_application_generation_active})
     */
    public UUID insert(UUID applicationId, boolean wantCv, boolean wantCoverLetter, boolean mentionNoticePeriod) {
        MapSqlParameterSource params = new MapSqlParameterSource("applicationId", applicationId)
                .addValue("wantCv", wantCv)
                .addValue("wantCoverLetter", wantCoverLetter)
                .addValue("mention", mentionNoticePeriod);
        return jdbc.queryForObject("INSERT INTO application_generation (application_id, want_cv, want_cover_letter, "
                + "mention_notice_period) VALUES (:applicationId, :wantCv, :wantCoverLetter, :mention) RETURNING id",
                params, UUID.class);
    }

    /**
     * PENDING → RUNNING, counting the attempt. False if another worker got it first, it is no longer
     * pending (or no longer exists), or it has used up {@link #MAX_ATTEMPTS} (see {@link #failExhausted}).
     */
    public boolean claim(UUID id, Instant now) {
        return jdbc.update("UPDATE application_generation SET status = 'RUNNING', attempts = attempts + 1, "
                + "started_at = :now, finished_at = NULL WHERE id = :id AND status = 'PENDING' AND attempts < :max",
                new MapSqlParameterSource("id", id).addValue("now", Timestamp.from(now))
                        .addValue("max", MAX_ATTEMPTS)) == 1;
    }

    /** PENDING with no attempts left → FAILED/TOO_MANY_ATTEMPTS. True if it changed the row. */
    public boolean failExhausted(UUID id, Instant now) {
        return jdbc.update("UPDATE application_generation SET status = 'FAILED', failure_reason = :reason, "
                + "finished_at = :now WHERE id = :id AND status = 'PENDING' AND attempts >= :max",
                new MapSqlParameterSource("id", id).addValue("reason", GenerationFailure.TOO_MANY_ATTEMPTS.name())
                        .addValue("now", Timestamp.from(now)).addValue("max", MAX_ATTEMPTS)) == 1;
    }

    /** Records the model and the profile version a RUNNING generation uses. True if it changed the row. */
    public boolean start(UUID id, String model, int profileVersion) {
        return jdbc.update("UPDATE application_generation SET model = :model, profile_version = :profileVersion "
                + "WHERE id = :id AND status = 'RUNNING'", new MapSqlParameterSource("id", id)
                .addValue("model", model).addValue("profileVersion", profileVersion)) == 1;
    }

    /**
     * Records one kind's result on a RUNNING generation: the stored version, or a per-kind failure (the
     * other column is cleared, so a re-run after recovery overwrites an earlier attempt's failure).
     *
     * @param documentId the stored version, or null
     * @param failure    a {@linkplain GenerationFailure#perKind() per-kind} failure, or null
     * @return true if it changed the row
     */
    public boolean finishKind(UUID id, DocumentKind kind, UUID documentId, GenerationFailure failure) {
        if ((documentId == null) == (failure == null)) {
            throw new IllegalArgumentException("exactly one of documentId and failure is required");
        }
        if (failure != null && !failure.perKind()) {
            throw new IllegalArgumentException(failure + " is not a per-kind failure");
        }
        String prefix = kind == DocumentKind.CV ? "cv" : "cover_letter";
        return jdbc.update("UPDATE application_generation SET " + prefix + "_document_id = :document, " + prefix
                + "_failure = :failure WHERE id = :id AND status = 'RUNNING'", new MapSqlParameterSource("id", id)
                .addValue("document", documentId, Types.OTHER)
                .addValue("failure", failure == null ? null : failure.name(), Types.VARCHAR)) == 1;
    }

    /** RUNNING → SUCCEEDED (at least one kind stored). True if it changed the row. */
    public boolean succeed(UUID id, String model, Instant now) {
        return jdbc.update("UPDATE application_generation SET status = 'SUCCEEDED', failure_reason = NULL, "
                + "model = :model, finished_at = :now WHERE id = :id AND status = 'RUNNING'",
                new MapSqlParameterSource("id", id).addValue("model", model)
                        .addValue("now", Timestamp.from(now))) == 1;
    }

    /**
     * PENDING or RUNNING → FAILED (PENDING when the queue rejects it). A null {@code model} keeps the
     * stored one. True if it changed the row.
     */
    public boolean fail(UUID id, GenerationFailure reason, String model, Instant now) {
        return jdbc.update("UPDATE application_generation SET status = 'FAILED', failure_reason = :reason, "
                + "model = COALESCE(:model, model), finished_at = :now "
                + "WHERE id = :id AND status IN ('PENDING', 'RUNNING')",
                new MapSqlParameterSource("id", id).addValue("reason", reason.name())
                        .addValue("model", model, Types.VARCHAR).addValue("now", Timestamp.from(now))) == 1;
    }

    /**
     * After a restart: RUNNING work was lost, so put it back in the queue. Per-kind results stay, so the
     * re-run skips a kind that was already stored.
     */
    public int resetRunning() {
        return jdbc.update("UPDATE application_generation SET status = 'PENDING', started_at = NULL "
                + "WHERE status = 'RUNNING'", new MapSqlParameterSource());
    }

    // ------------------------------------------------------------------ helpers

    private static GenerationFailure failure(String value) {
        return value == null ? null : GenerationFailure.valueOf(value);
    }
}
