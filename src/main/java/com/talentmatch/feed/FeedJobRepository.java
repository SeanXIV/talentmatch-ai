package com.talentmatch.feed;

import static com.talentmatch.feed.JobPostingRepository.chunks;
import static com.talentmatch.feed.JobPostingRepository.instant;
import static com.talentmatch.feed.JobPostingRepository.ts;

import com.talentmatch.feed.source.SourceKind;
import com.talentmatch.preferences.SalaryPeriod;
import com.talentmatch.preferences.Seniority;
import com.talentmatch.preferences.Workplace;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.SqlTypeValue;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.support.AbstractSqlTypeValue;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * JDBC access to {@code feed_job} and its {@code job} row (origin FEED). Step 6 covers what the
 * poller writes: create / attach by dedup key, canonical fields, open / close, and
 * {@code process_after} (the hand-off to the processor). Step 7 adds the processor's claim, the
 * job_skill diff, the processed state, the refresh marking and the {@code GET /api/feed/jobs} reads.
 */
@Repository
public class FeedJobRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public FeedJobRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** A feed job's key and whether it is closed. */
    public record JobRef(UUID jobId, String dedupKey, Instant closedAt) {
    }

    /** The open feed job with this dedup key (the partial unique index allows at most one). */
    public Optional<UUID> findOpenByDedupKey(String dedupKey) {
        return jdbc.queryForList("SELECT job_id FROM feed_job WHERE dedup_key = :key AND closed_at IS NULL",
                new MapSqlParameterSource("key", dedupKey), UUID.class).stream().findFirst();
    }

    public Optional<JobRef> findRef(UUID jobId) {
        return jdbc.query("SELECT job_id, dedup_key, closed_at FROM feed_job WHERE job_id = :id",
                new MapSqlParameterSource("id", jobId),
                (rs, n) -> new JobRef(rs.getObject("job_id", UUID.class), rs.getString("dedup_key"),
                        instant(rs, "closed_at"))).stream().findFirst();
    }

    /**
     * Creates a FEED job and its feed_job row for a new posting. The canonical fields are filled by
     * {@link #applyCanonical} afterwards (same transaction).
     *
     * @return the new job id, or empty when another transaction created an open job with this key
     *         first (the partial unique index decided; this method then removes its own job row)
     */
    public Optional<UUID> create(NormalizedPosting p, String dedupKey, boolean baseline, Instant now) {
        UUID jobId = jdbc.queryForObject("INSERT INTO job (title, company, description, origin) "
                        + "VALUES (:title, :company, :description, 'FEED') RETURNING id",
                new MapSqlParameterSource("title", p.title()).addValue("company", p.company())
                        .addValue("description", p.description(), Types.VARCHAR), UUID.class);
        List<UUID> inserted = jdbc.queryForList("INSERT INTO feed_job (job_id, dedup_key, first_seen_at, posted_at, "
                        + "baseline, primary_url, process_after) VALUES (:id, :key, :now, :postedAt, :baseline, :url, :now) "
                        + "ON CONFLICT (dedup_key) WHERE closed_at IS NULL DO NOTHING RETURNING job_id",
                new MapSqlParameterSource("id", jobId)
                        .addValue("key", dedupKey)
                        .addValue("now", ts(now), Types.TIMESTAMP)
                        .addValue("postedAt", ts(p.postedAt()), Types.TIMESTAMP)
                        .addValue("baseline", baseline)
                        .addValue("url", p.url()), UUID.class);
        if (inserted.isEmpty()) {
            jdbc.update("DELETE FROM job WHERE id = :id AND origin = 'FEED'", new MapSqlParameterSource("id", jobId));
            return Optional.empty();
        }
        return Optional.of(jobId);
    }

    /** A posting that isn't baseline joined the job: the job is no longer baseline (§4.5). */
    public void clearBaseline(UUID jobId) {
        jdbc.update("UPDATE feed_job SET baseline = false WHERE job_id = :id AND baseline",
                new MapSqlParameterSource("id", jobId));
    }

    /**
     * Reopens a closed job, unless another open job holds its key (then the caller moves the posting
     * to that job).
     *
     * @return true when reopened
     */
    public boolean reopen(UUID jobId, Instant now) {
        return jdbc.update("UPDATE feed_job f SET closed_at = NULL, process_after = :now WHERE f.job_id = :id "
                        + "AND f.closed_at IS NOT NULL AND NOT EXISTS (SELECT 1 FROM feed_job o "
                        + "WHERE o.dedup_key = f.dedup_key AND o.closed_at IS NULL)",
                new MapSqlParameterSource("id", jobId).addValue("now", ts(now), Types.TIMESTAMP)) == 1;
    }

    /**
     * Closes the jobs among {@code jobIds} that have no open posting left.
     *
     * @return the jobs closed now
     */
    public Set<UUID> closeWithoutOpenPostings(Collection<UUID> jobIds, Instant now) {
        Set<UUID> closed = new LinkedHashSet<>();
        for (List<UUID> chunk : chunks(sortedForDatabase(jobIds))) {
            closed.addAll(jdbc.queryForList("UPDATE feed_job f SET closed_at = :now WHERE f.job_id IN (:ids) "
                            + "AND f.closed_at IS NULL AND NOT EXISTS (SELECT 1 FROM job_posting p "
                            + "WHERE p.job_id = f.job_id AND p.closed_at IS NULL) RETURNING f.job_id",
                    new MapSqlParameterSource("ids", chunk).addValue("now", ts(now), Types.TIMESTAMP), UUID.class));
        }
        return closed;
    }

    /**
     * Writes the canonical fields (§4.4) and hands the job to the processor ({@code process_after}).
     * When the description changed ({@code description_hash} differs), AI enrichment starts over:
     * PENDING, attempts 0, no failure, no wait. The {@code job} row is only updated when its title,
     * company or description changed, so an unchanged job keeps its {@code updated_at} (cached
     * matches stay fresh).
     */
    public void applyCanonical(UUID jobId, CanonicalJob.Canonical c, Instant now) {
        jdbc.update("UPDATE job SET title = :title, company = :company, description = :description "
                        + "WHERE id = :id AND origin = 'FEED' AND (title IS DISTINCT FROM :title "
                        + "OR company IS DISTINCT FROM :company OR description IS DISTINCT FROM :description)",
                new MapSqlParameterSource("id", jobId).addValue("title", c.title()).addValue("company", c.company())
                        .addValue("description", c.description(), Types.VARCHAR));

        String changed = "description_hash IS DISTINCT FROM CAST(:hash AS char(64))";
        MapSqlParameterSource params = new MapSqlParameterSource("id", jobId)
                .addValue("hash", c.descriptionHash(), Types.VARCHAR)
                .addValue("postedAt", ts(c.postedAt()), Types.TIMESTAMP)
                .addValue("url", c.primaryUrl())
                .addValue("location", c.locationText(), Types.VARCHAR)
                .addValue("countries", String.join(",", c.countryCodes()))
                .addValue("workplace", c.workplace().name())
                .addValue("employmentType", c.employmentType(), Types.VARCHAR)
                .addValue("seniority", c.seniority().name())
                .addValue("salaryMin", c.salaryMin(), Types.NUMERIC)
                .addValue("salaryMax", c.salaryMax(), Types.NUMERIC)
                .addValue("currency", c.salaryCurrency(), Types.VARCHAR)
                .addValue("period", c.salaryPeriod() == null ? null : c.salaryPeriod().name(), Types.VARCHAR)
                .addValue("estimated", c.salaryEstimated())
                .addValue("now", ts(now), Types.TIMESTAMP);
        jdbc.update("UPDATE feed_job SET posted_at = :postedAt, primary_url = :url, location_text = :location, "
                + "country_codes = CAST(string_to_array(CAST(:countries AS text), ',') AS varchar(2)[]), "
                + "workplace = :workplace, employment_type = :employmentType, seniority = :seniority, "
                + "salary_min = :salaryMin, salary_max = :salaryMax, salary_currency = :currency, "
                + "salary_period = :period, salary_estimated = :estimated, "
                + "enrichment_status = CASE WHEN " + changed + " THEN 'PENDING' ELSE enrichment_status END, "
                + "enrichment_attempts = CASE WHEN " + changed + " THEN 0 ELSE enrichment_attempts END, "
                + "enrichment_failure = CASE WHEN " + changed + " THEN NULL ELSE enrichment_failure END, "
                + "enrichment_not_before = CASE WHEN " + changed + " THEN NULL ELSE enrichment_not_before END, "
                + "description_hash = CAST(:hash AS char(64)), process_after = :now "
                + "WHERE job_id = :id", params);
    }

    /** Feed jobs waiting for the processor (step 7). */
    public long countPendingProcessing() {
        Long count = jdbc.queryForObject("SELECT count(*) FROM feed_job WHERE process_after IS NOT NULL",
                new MapSqlParameterSource(), Long.class);
        return count == null ? 0 : count;
    }

    // ------------------------------------------------------------------ processing (step 7)

    /** Jobs due for processing ({@code process_after <= now}), oldest request first. */
    public List<UUID> findDue(Instant now, int limit) {
        return jdbc.queryForList("SELECT job_id FROM feed_job WHERE process_after IS NOT NULL AND process_after <= :now "
                        + "ORDER BY process_after, job_id LIMIT :limit",
                new MapSqlParameterSource().addValue("now", ts(now), Types.TIMESTAMP).addValue("limit", limit),
                UUID.class);
    }

    /**
     * What the processor reads about one job, locked.
     *
     * @param aiSkillsJson feed_job.ai_skills as JSON text (null = not enriched)
     */
    public record ProcessingRow(UUID jobId, String title, String description, Instant firstSeenAt, Instant closedAt,
                                boolean baseline, List<String> countryCodes, Workplace workplace, String locationText,
                                Seniority seniority, BigDecimal salaryMax, String salaryCurrency,
                                SalaryPeriod salaryPeriod, boolean salaryEstimated, String aiSkillsJson) {
    }

    /**
     * Locks a job that still needs processing ({@code FOR NO KEY UPDATE} of the feed_job and job rows,
     * {@code SKIP LOCKED}). Must run inside a transaction.
     *
     * <p>NO KEY UPDATE, not UPDATE: it blocks the poller's writes to these rows until the transaction
     * ends, but not the key-share locks that inserting a {@code job_match} row takes on the job, so a
     * {@code GET /api/jobs/{id}/matches} that holds the job's match lock never waits on this row lock
     * while this transaction waits on the match lock.
     *
     * @return empty when the job is gone, no longer due, or locked by someone else (a poll writing it)
     */
    public Optional<ProcessingRow> lockForProcessing(UUID jobId) {
        return jdbc.query("SELECT f.job_id, j.title, j.description, f.first_seen_at, f.closed_at, f.baseline, "
                        + "array_to_string(f.country_codes, ',') AS country_codes, f.workplace, f.location_text, "
                        + "f.seniority, f.salary_max, f.salary_currency, f.salary_period, f.salary_estimated, "
                        + "f.ai_skills::text AS ai_skills FROM feed_job f JOIN job j ON j.id = f.job_id "
                        + "WHERE f.job_id = :id AND f.process_after IS NOT NULL "
                        + "FOR NO KEY UPDATE OF f, j SKIP LOCKED",
                new MapSqlParameterSource("id", jobId),
                (rs, n) -> new ProcessingRow(
                        rs.getObject("job_id", UUID.class),
                        rs.getString("title"),
                        rs.getString("description"),
                        instant(rs, "first_seen_at"),
                        instant(rs, "closed_at"),
                        rs.getBoolean("baseline"),
                        codes(rs.getString("country_codes")),
                        Workplace.valueOf(rs.getString("workplace")),
                        rs.getString("location_text"),
                        Seniority.valueOf(rs.getString("seniority")),
                        rs.getBigDecimal("salary_max"),
                        trimmed(rs.getString("salary_currency")),
                        period(rs.getString("salary_period")),
                        rs.getBoolean("salary_estimated"),
                        rs.getString("ai_skills"))).stream().findFirst();
    }

    /**
     * Makes {@code job_skill} hold exactly these skills (skill id → required), touching only rows that
     * differ, so an unchanged job keeps its {@code updated_at} and its cached matches stay fresh (V2
     * triggers). Skills deleted meanwhile are skipped.
     */
    public void syncJobSkills(UUID jobId, Map<UUID, Boolean> skills) {
        UUID[] ids = skills.keySet().toArray(new UUID[0]);
        Boolean[] required = new Boolean[ids.length];
        for (int i = 0; i < ids.length; i++) {
            required[i] = Boolean.TRUE.equals(skills.get(ids[i]));
        }
        MapSqlParameterSource params = new MapSqlParameterSource("jobId", jobId)
                .addValue("ids", sqlArray("uuid", ids))
                .addValue("required", sqlArray("boolean", required));
        jdbc.update("DELETE FROM job_skill WHERE job_id = :jobId AND NOT (skill_id = ANY(CAST(:ids AS uuid[])))", params);
        if (ids.length > 0) {
            jdbc.update("INSERT INTO job_skill (job_id, skill_id, required) "
                    + "SELECT :jobId, u.skill_id, u.required "
                    + "FROM unnest(CAST(:ids AS uuid[]), CAST(:required AS boolean[])) AS u(skill_id, required) "
                    + "JOIN skill s ON s.id = u.skill_id "
                    + "ON CONFLICT (job_id, skill_id) DO UPDATE SET required = EXCLUDED.required "
                    + "WHERE job_skill.required IS DISTINCT FROM EXCLUDED.required", params);
        }
    }

    /**
     * The outcome of processing one job.
     *
     * @param preferencesVersion the preferences version evaluated against (null = none saved)
     * @param profileVersion     the owner profile version scored against (null = no profile)
     * @param scoredAt           when the owner's score was written (null = not scored)
     */
    public record Processed(String dictionarySkillsJson, String verdict, String reasonsJson, String flagsJson,
                            Integer preferencesVersion, Integer profileVersion, Instant scoredAt) {
    }

    /** Stores the outcome and clears {@code process_after}. Call under {@link #lockForProcessing}. */
    public void markProcessed(UUID jobId, Processed p) {
        jdbc.update("UPDATE feed_job SET dictionary_skills = CAST(:dict AS jsonb), preference_verdict = :verdict, "
                        + "filter_reasons = CAST(:reasons AS jsonb), filter_flags = CAST(:flags AS jsonb), "
                        + "evaluated_preferences_version = :prefsVersion, scored_profile_version = :profileVersion, "
                        + "scored_at = :scoredAt, process_after = NULL WHERE job_id = :id",
                new MapSqlParameterSource("id", jobId)
                        .addValue("dict", p.dictionarySkillsJson())
                        .addValue("verdict", p.verdict())
                        .addValue("reasons", p.reasonsJson())
                        .addValue("flags", p.flagsJson())
                        .addValue("prefsVersion", p.preferencesVersion(), Types.INTEGER)
                        .addValue("profileVersion", p.profileVersion(), Types.INTEGER)
                        .addValue("scoredAt", ts(p.scoredAt()), Types.TIMESTAMP));
    }

    /** Processing failed: try again at {@code until} (the job stays pending; autocommit). */
    public void deferProcessing(UUID jobId, Instant until) {
        jdbc.update("UPDATE feed_job SET process_after = :until WHERE job_id = :id AND process_after IS NOT NULL",
                new MapSqlParameterSource("id", jobId).addValue("until", ts(until), Types.TIMESTAMP));
    }

    /**
     * Marks every open job for processing (§4.11 refresh triggers). Jobs already due earlier keep
     * their place in the queue ({@code process_after = LEAST(process_after, now)}).
     *
     * <p>Every open job is locked and written, including the ones already due: a run of the processor
     * may be about to evaluate them against what it read before this change, and the write (or the
     * wait for the processor's row lock, then the write) guarantees they are pending once more after
     * that run has cleared them. Skipping them would leave a stale verdict that no later run fixes,
     * because the change is recorded as applied in {@code feed_state} by the same transaction.
     *
     * <p>The rows are locked first in {@code job_id} order ({@code FOR NO KEY UPDATE}, the lock the
     * UPDATE itself takes, so key-share locks from foreign keys aren't blocked), and only then updated.
     * A plain UPDATE would lock them in physical order and deadlock with a poll writing the same jobs
     * in another order; {@link PollWriter} locks in {@code job_id} order too. A row changed by the
     * transaction waited on is re-checked (still open) before it is locked, and the UPDATE computes the
     * new {@code process_after} from its latest version (NULL after the processor cleared it).
     *
     * @return the number of open jobs marked (every open job)
     */
    public int markOpenForProcessing(Instant now) {
        return jdbc.update("WITH t AS (SELECT job_id FROM feed_job WHERE closed_at IS NULL "
                        + "ORDER BY job_id FOR NO KEY UPDATE) "
                        + "UPDATE feed_job f SET process_after = LEAST(COALESCE(f.process_after, :now), :now) "
                        + "FROM t WHERE f.job_id = t.job_id",
                new MapSqlParameterSource().addValue("now", ts(now), Types.TIMESTAMP));
    }

    /**
     * Locks these jobs' open feed_job rows in {@code job_id} order ({@code FOR NO KEY UPDATE}), so a
     * poll that writes them afterwards in any order can't deadlock with a refresh marker. Chunks of a
     * sorted list keep the order across statements. Must run inside a transaction.
     */
    public void lockOpenInOrder(Collection<UUID> jobIds) {
        for (List<UUID> chunk : chunks(sortedForDatabase(jobIds))) {
            jdbc.queryForList("SELECT job_id FROM feed_job WHERE job_id IN (:ids) AND closed_at IS NULL "
                    + "ORDER BY job_id FOR NO KEY UPDATE", new MapSqlParameterSource("ids", chunk), UUID.class);
        }
    }

    /**
     * Distinct ids in PostgreSQL's {@code uuid} order (unsigned, byte by byte). {@link UUID#compareTo}
     * compares signed halves and would disagree for ids with the high bit set.
     */
    static List<UUID> sortedForDatabase(Collection<UUID> ids) {
        return ids.stream().distinct().sorted(DATABASE_ORDER).toList();
    }

    /** PostgreSQL's ordering of {@code uuid} values. */
    static final java.util.Comparator<UUID> DATABASE_ORDER = (a, b) -> {
        int c = Long.compareUnsigned(a.getMostSignificantBits(), b.getMostSignificantBits());
        return c != 0 ? c : Long.compareUnsigned(a.getLeastSignificantBits(), b.getLeastSignificantBits());
    };

    // ------------------------------------------------------------------ feed API (step 7)

    /** The filters of {@code GET /api/feed/jobs}; {@code ownerId} null = no owner profile (no scores). */
    public record ListQuery(Instant since, Double minScore, boolean includeFiltered, boolean includeClosed,
                            boolean includeBaseline, UUID ownerId) {
    }

    /** One feed job as the API shows it, before its skills, postings and evaluation are added. */
    public record JobRow(UUID jobId, String title, String company, String description, String primaryUrl,
                         Instant firstSeenAt, Instant postedAt, Instant closedAt, boolean baseline, Workplace workplace,
                         String locationText, List<String> countryCodes, Seniority seniority, BigDecimal salaryMin,
                         BigDecimal salaryMax, String salaryCurrency, SalaryPeriod salaryPeriod,
                         boolean salaryEstimated, String dictionarySkillsJson, String aiSkillsJson,
                         String aiSuggestionsJson, String enrichmentStatus, String preferenceVerdict,
                         String filterReasonsJson, String filterFlagsJson, Double score, String notificationStatus,
                         String notificationChannel, Instant notificationSentAt) {
    }

    /** A job's skill as stored in job_skill (current skill name). */
    public record SkillRow(UUID jobId, UUID skillId, String name, boolean required) {
    }

    /** A posting of a feed job with its source kind. */
    public record PostingRow(UUID jobId, SourceKind kind, String url, String externalId, Instant firstSeenAt,
                             Instant closedAt) {
    }

    private static final String JOB_COLUMNS = "f.job_id, j.title, j.company, f.primary_url, f.first_seen_at, "
            + "f.posted_at, f.closed_at, f.baseline, f.workplace, f.location_text, "
            + "array_to_string(f.country_codes, ',') AS country_codes, f.seniority, f.salary_min, f.salary_max, "
            + "f.salary_currency, f.salary_period, f.salary_estimated, f.dictionary_skills::text AS dictionary_skills, "
            + "f.ai_skills::text AS ai_skills, f.ai_suggestions::text AS ai_suggestions, f.enrichment_status, "
            + "f.preference_verdict, f.filter_reasons::text AS filter_reasons, f.filter_flags::text AS filter_flags, "
            + "n.status AS notification_status, n.channel AS notification_channel, n.sent_at AS notification_sent_at";

    public long count(ListQuery q) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        Long count = jdbc.queryForObject("SELECT count(*) FROM feed_job f" + scoreJoin(q.ownerId(), params)
                + where(q, params), params, Long.class);
        return count == null ? 0 : count;
    }

    /** A page of feed jobs, newest first seen first. */
    public List<JobRow> findPage(ListQuery q, long offset, int limit) {
        MapSqlParameterSource params = new MapSqlParameterSource().addValue("limit", limit).addValue("offset", offset);
        String join = scoreJoin(q.ownerId(), params);
        return jdbc.query("SELECT " + JOB_COLUMNS + ", NULL AS description, " + scoreColumn(q.ownerId())
                        + " FROM feed_job f JOIN job j ON j.id = f.job_id" + join
                        + " LEFT JOIN feed_notification n ON n.job_id = f.job_id" + where(q, params)
                        + " ORDER BY f.first_seen_at DESC, f.job_id DESC LIMIT :limit OFFSET :offset",
                params, (rs, i) -> jobRow(rs));
    }

    /** One feed job with its description; empty when there is no feed job with this id. */
    public Optional<JobRow> findJob(UUID jobId, UUID ownerId) {
        MapSqlParameterSource params = new MapSqlParameterSource("id", jobId);
        return jdbc.query("SELECT " + JOB_COLUMNS + ", j.description, " + scoreColumn(ownerId)
                        + " FROM feed_job f JOIN job j ON j.id = f.job_id" + scoreJoin(ownerId, params)
                        + " LEFT JOIN feed_notification n ON n.job_id = f.job_id WHERE f.job_id = :id",
                params, (rs, i) -> jobRow(rs)).stream().findFirst();
    }

    /** True when a job (of any origin) has this id. */
    public boolean jobExists(UUID jobId) {
        Boolean exists = jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM job WHERE id = :id)",
                new MapSqlParameterSource("id", jobId), Boolean.class);
        return Boolean.TRUE.equals(exists);
    }

    /** The job_skill rows of these jobs, by name. */
    public List<SkillRow> findSkills(Collection<UUID> jobIds) {
        if (jobIds.isEmpty()) {
            return List.of();
        }
        return jdbc.query("SELECT js.job_id, js.skill_id, s.name, js.required FROM job_skill js "
                        + "JOIN skill s ON s.id = js.skill_id WHERE js.job_id IN (:ids) ORDER BY lower(s.name), s.id",
                new MapSqlParameterSource("ids", List.copyOf(jobIds)),
                (rs, i) -> new SkillRow(rs.getObject("job_id", UUID.class), rs.getObject("skill_id", UUID.class),
                        rs.getString("name"), rs.getBoolean("required")));
    }

    /** The postings (open and closed) of these jobs, oldest first. */
    public List<PostingRow> findPostings(Collection<UUID> jobIds) {
        if (jobIds.isEmpty()) {
            return List.of();
        }
        return jdbc.query("SELECT p.job_id, s.kind, p.url, p.external_id, p.first_seen_at, p.closed_at "
                        + "FROM job_posting p JOIN feed_source s ON s.id = p.source_id WHERE p.job_id IN (:ids) "
                        + "ORDER BY p.first_seen_at, p.id",
                new MapSqlParameterSource("ids", List.copyOf(jobIds)),
                (rs, i) -> new PostingRow(rs.getObject("job_id", UUID.class), SourceKind.valueOf(rs.getString("kind")),
                        rs.getString("url"), rs.getString("external_id"), instant(rs, "first_seen_at"),
                        instant(rs, "closed_at")));
    }

    private static String scoreColumn(UUID ownerId) {
        return ownerId == null ? "CAST(NULL AS double precision) AS score" : "m.score AS score";
    }

    private static String scoreJoin(UUID ownerId, MapSqlParameterSource params) {
        if (ownerId == null) {
            return "";
        }
        params.addValue("ownerId", ownerId);
        return " LEFT JOIN job_match m ON m.job_id = f.job_id AND m.candidate_id = :ownerId";
    }

    private static String where(ListQuery q, MapSqlParameterSource params) {
        List<String> conditions = new ArrayList<>();
        if (q.since() != null) {
            conditions.add("f.first_seen_at >= :since");
            params.addValue("since", ts(q.since()), Types.TIMESTAMP);
        }
        if (q.minScore() != null) {
            if (q.ownerId() == null) {
                conditions.add("false");                    // nothing is scored without an owner profile
            } else {
                // A job without skills has no score, whatever an old match row says.
                conditions.add("m.score >= :minScore AND EXISTS (SELECT 1 FROM job_skill js WHERE js.job_id = f.job_id)");
                params.addValue("minScore", q.minScore());
            }
        }
        if (!q.includeFiltered()) {
            conditions.add("f.preference_verdict IS DISTINCT FROM 'FILTERED'");
        }
        if (!q.includeClosed()) {
            conditions.add("f.closed_at IS NULL");
        }
        if (!q.includeBaseline()) {
            conditions.add("NOT f.baseline");
        }
        return conditions.isEmpty() ? "" : " WHERE " + String.join(" AND ", conditions);
    }

    private static JobRow jobRow(ResultSet rs) throws SQLException {
        double score = rs.getDouble("score");
        Double scoreOrNull = rs.wasNull() ? null : score;
        return new JobRow(
                rs.getObject("job_id", UUID.class),
                rs.getString("title"),
                rs.getString("company"),
                rs.getString("description"),
                rs.getString("primary_url"),
                instant(rs, "first_seen_at"),
                instant(rs, "posted_at"),
                instant(rs, "closed_at"),
                rs.getBoolean("baseline"),
                Workplace.valueOf(rs.getString("workplace")),
                rs.getString("location_text"),
                codes(rs.getString("country_codes")),
                Seniority.valueOf(rs.getString("seniority")),
                rs.getBigDecimal("salary_min"),
                rs.getBigDecimal("salary_max"),
                trimmed(rs.getString("salary_currency")),
                period(rs.getString("salary_period")),
                rs.getBoolean("salary_estimated"),
                rs.getString("dictionary_skills"),
                rs.getString("ai_skills"),
                rs.getString("ai_suggestions"),
                rs.getString("enrichment_status"),
                rs.getString("preference_verdict"),
                rs.getString("filter_reasons"),
                rs.getString("filter_flags"),
                scoreOrNull,
                rs.getString("notification_status"),
                rs.getString("notification_channel"),
                instant(rs, "notification_sent_at"));
    }

    private static List<String> codes(String joined) {
        if (joined == null || joined.isBlank()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String c : joined.split(",")) {
            String s = c.strip();
            if (!s.isEmpty()) {
                out.add(s);
            }
        }
        return List.copyOf(out);
    }

    private static String trimmed(String value) {
        return value == null ? null : value.strip();
    }

    private static SalaryPeriod period(String value) {
        return value == null ? null : SalaryPeriod.valueOf(value.strip());
    }

    /** Binds a Java array as a PostgreSQL array created on the statement's own connection. */
    private static SqlTypeValue sqlArray(String elementType, Object[] elements) {
        return new AbstractSqlTypeValue() {
            @Override
            protected Object createTypeValue(Connection con, int sqlType, String typeName) throws SQLException {
                return con.createArrayOf(elementType, elements);
            }
        };
    }
}
