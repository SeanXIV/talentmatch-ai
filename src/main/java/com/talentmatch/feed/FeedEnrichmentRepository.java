package com.talentmatch.feed;

import com.talentmatch.feed.skills.EnrichmentFailure;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * JDBC access to the enrichment columns of {@code feed_job} (V5, §4.7). Every statement touches one
 * row, or a set of rows without waiting on locks, so it can't deadlock with the processor or the
 * refresh markers, which lock many rows in {@code job_id} order. A finishing write only applies while
 * the row is still RUNNING with the description it was claimed with: a description that changed
 * meanwhile reset the row to PENDING, and the stale answer is discarded.
 */
@Repository
public class FeedEnrichmentRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public FeedEnrichmentRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * A claimed job: what the prompt needs and the claim's guard values.
     *
     * @param descriptionHash the canonical description's hash at claim time (the finishing guard)
     * @param attempts        calls made so far, this one included
     */
    public record Task(UUID jobId, String title, String company, String description, String descriptionHash,
                       int attempts) {
    }

    /** Open PENDING jobs that already used every attempt (interrupted repeatedly) → FAILED/TOO_MANY_ATTEMPTS. */
    public int failExhausted(int maxAttempts) {
        return jdbc.update("UPDATE feed_job SET enrichment_status = 'FAILED', enrichment_failure = 'TOO_MANY_ATTEMPTS' "
                + "WHERE enrichment_status = 'PENDING' AND enrichment_attempts >= :max",
                new MapSqlParameterSource("max", maxAttempts));
    }

    /** AI disabled: every PENDING job keeps its dictionary skills only (SKIPPED). @return rows */
    public int skipPending() {
        return jdbc.update("UPDATE feed_job SET enrichment_status = 'SKIPPED', enrichment_not_before = NULL "
                + "WHERE enrichment_status = 'PENDING'", new MapSqlParameterSource());
    }

    /**
     * PENDING jobs without a description have nothing to read → SKIPPED (dictionary only), so they
     * don't wait forever. A description arriving later resets them to PENDING ({@code applyCanonical}).
     * @return rows
     */
    public int skipNoDescription() {
        return jdbc.update("UPDATE feed_job f SET enrichment_status = 'SKIPPED', enrichment_not_before = NULL "
                + "FROM job j WHERE j.id = f.job_id AND f.enrichment_status = 'PENDING' AND j.description IS NULL",
                new MapSqlParameterSource());
    }

    /**
     * Claims the next due job (PENDING, open, with a description, wait over, attempts left): jobs that
     * pass the preferences first, then the newest. RUNNING, one attempt counted. Skips locked rows.
     */
    public Optional<Task> claim(int maxAttempts, Instant now) {
        MapSqlParameterSource params = new MapSqlParameterSource("max", maxAttempts)
                .addValue("now", ts(now), Types.TIMESTAMP);
        return jdbc.query("UPDATE feed_job f SET enrichment_status = 'RUNNING', "
                        + "enrichment_attempts = f.enrichment_attempts + 1, enrichment_not_before = NULL "
                        + "FROM job j WHERE j.id = f.job_id AND f.job_id = (SELECT c.job_id FROM feed_job c "
                        + "JOIN job cj ON cj.id = c.job_id WHERE c.enrichment_status = 'PENDING' "
                        + "AND c.closed_at IS NULL AND cj.description IS NOT NULL AND c.enrichment_attempts < :max "
                        + "AND (c.enrichment_not_before IS NULL OR c.enrichment_not_before <= :now) "
                        + "ORDER BY (c.preference_verdict = 'PASS') DESC NULLS LAST, c.first_seen_at DESC, c.job_id "
                        + "LIMIT 1 FOR UPDATE OF c SKIP LOCKED) "
                        + "RETURNING f.job_id, j.title, j.company, j.description, f.description_hash, "
                        + "f.enrichment_attempts", params,
                (rs, n) -> new Task(rs.getObject("job_id", UUID.class), rs.getString("title"),
                        rs.getString("company"), rs.getString("description"), rs.getString("description_hash"),
                        rs.getInt("enrichment_attempts"))).stream().findFirst();
    }

    /**
     * Back to PENDING until {@code notBefore}. {@code uncount}: the attempt is given back, because the
     * call was never made (model busy), the provider was at fault (the stage pause bounds those
     * retries), or the answer could not be stored (database error).
     */
    public boolean release(UUID jobId, Instant notBefore, boolean uncount) {
        return jdbc.update("UPDATE feed_job SET enrichment_status = 'PENDING', enrichment_not_before = :notBefore"
                        + (uncount ? ", enrichment_attempts = GREATEST(enrichment_attempts - 1, 0)" : "")
                        + " WHERE job_id = :id AND enrichment_status = 'RUNNING'",
                new MapSqlParameterSource("id", jobId).addValue("notBefore", ts(notBefore), Types.TIMESTAMP)) == 1;
    }

    /** RUNNING → FAILED with the reason and the model that failed. */
    public boolean fail(UUID jobId, EnrichmentFailure failure, String model) {
        return jdbc.update("UPDATE feed_job SET enrichment_status = 'FAILED', enrichment_failure = :failure, "
                        + "enrichment_model = :model, enrichment_not_before = NULL "
                        + "WHERE job_id = :id AND enrichment_status = 'RUNNING'",
                new MapSqlParameterSource("id", jobId).addValue("failure", failure.name())
                        .addValue("model", model, Types.VARCHAR)) == 1;
    }

    /**
     * RUNNING → SUCCEEDED with the AI skills and suggestions, and hands the job back to the processor
     * ({@code process_after}) so it is re-scored with them.
     *
     * @return false when the row changed meanwhile (new description, closed, deleted): nothing written
     */
    public boolean succeed(Task task, String aiSkillsJson, String suggestionsJson, String model, Instant now) {
        MapSqlParameterSource params = new MapSqlParameterSource("id", task.jobId())
                .addValue("hash", task.descriptionHash(), Types.VARCHAR)
                .addValue("skills", aiSkillsJson)
                .addValue("suggestions", suggestionsJson)
                .addValue("model", model)
                .addValue("now", ts(now), Types.TIMESTAMP);
        return jdbc.update("UPDATE feed_job SET enrichment_status = 'SUCCEEDED', enrichment_failure = NULL, "
                + "ai_skills = CAST(:skills AS jsonb), ai_suggestions = CAST(:suggestions AS jsonb), "
                + "enrichment_model = :model, enriched_at = :now, enrichment_not_before = NULL, process_after = :now "
                + "WHERE job_id = :id AND enrichment_status = 'RUNNING' "
                + "AND description_hash IS NOT DISTINCT FROM CAST(:hash AS char(64))", params) == 1;
    }

    /** Startup: a RUNNING row belonged to a call that no longer runs → PENDING (attempts kept). @return rows */
    public int resetRunning() {
        return jdbc.update("UPDATE feed_job SET enrichment_status = 'PENDING' WHERE enrichment_status = 'RUNNING'",
                new MapSqlParameterSource());
    }

    /**
     * Startup with a model: open jobs skipped while AI was off get their turn. Jobs skipped for having no
     * description stay SKIPPED (they would only be skipped again). @return rows
     */
    public int resetSkipped() {
        return jdbc.update("UPDATE feed_job f SET enrichment_status = 'PENDING', enrichment_attempts = 0 "
                + "FROM job j WHERE j.id = f.job_id AND f.enrichment_status = 'SKIPPED' AND f.closed_at IS NULL "
                + "AND j.description IS NOT NULL", new MapSqlParameterSource());
    }

    /** Counts for {@code GET /api/feed/status} (open jobs only). */
    public record Counts(long pending, long running, long failed) {
    }

    public Counts counts() {
        return jdbc.queryForObject("SELECT count(*) FILTER (WHERE enrichment_status = 'PENDING') AS pending, "
                        + "count(*) FILTER (WHERE enrichment_status = 'RUNNING') AS running, "
                        + "count(*) FILTER (WHERE enrichment_status = 'FAILED') AS failed "
                        + "FROM feed_job WHERE closed_at IS NULL", new MapSqlParameterSource(),
                (rs, n) -> new Counts(rs.getLong("pending"), rs.getLong("running"), rs.getLong("failed")));
    }

    private static Timestamp ts(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }
}
