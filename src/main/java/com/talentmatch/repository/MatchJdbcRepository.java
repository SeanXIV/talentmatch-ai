package com.talentmatch.repository;

import com.talentmatch.ai.Audience;
import com.talentmatch.domain.scoring.CandidateSkillFact;
import com.talentmatch.domain.scoring.JobRequirement;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.SqlTypeValue;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.support.AbstractSqlTypeValue;
import org.springframework.stereotype.Repository;

/**
 * Set-based SQL for match caching: staleness, upsert, ranked pages, skill facts and the
 * per-job advisory lock. All methods join the caller's transaction (if any).
 */
@Repository
public class MatchJdbcRepository {

    /** Rows per upsert statement. */
    static final int UPSERT_CHUNK = 5000;

    /** Seconds to wait for the per-job advisory lock before giving up (MATCHES_BUSY). */
    static final String LOCK_TIMEOUT = "10s";

    private static final String SQL_JOB = """
            SELECT id, title, company, description, updated_at FROM job WHERE id = :jobId""";

    private static final String SQL_REQUIREMENTS = """
            SELECT js.skill_id, s.name, js.required
            FROM job_skill js JOIN skill s ON s.id = js.skill_id
            WHERE js.job_id = :jobId""";

    // Stale = no row yet, or computed before the latest change of the candidate or the job.
    // The job's updated_at is read in the same statement (same snapshot) as the match rows.
    private static final String SQL_STALE_CANDIDATES = """
            SELECT c.id FROM candidate c
            JOIN job j ON j.id = :jobId
            LEFT JOIN job_match m ON m.candidate_id = c.id AND m.job_id = j.id
            WHERE m.id IS NULL OR m.computed_at < GREATEST(c.updated_at, j.updated_at)
            ORDER BY c.id""";

    private static final String SQL_ALL_CANDIDATES = "SELECT id FROM candidate ORDER BY id";

    private static final String SQL_ANY_CANDIDATE = "SELECT EXISTS (SELECT 1 FROM candidate)";

    private static final String SQL_ALL_JOB_IDS = "SELECT id FROM job ORDER BY id";

    private static final String SQL_FACTS = """
            SELECT cs.candidate_id, cs.skill_id, cs.years_experience
            FROM candidate_skill cs
            WHERE cs.skill_id = ANY(:skillIds)""";

    private static final String SQL_FACTS_FOR_CANDIDATES =
            SQL_FACTS + " AND cs.candidate_id = ANY(:candidateIds)";

    // Joined to candidate so rows for candidates deleted in the meantime are skipped instead
    // of failing the whole statement on the foreign key. computed_at always advances;
    // ai_explanation and the explanation_* columns (V3) are never touched.
    private static final String SQL_UPSERT = """
            INSERT INTO job_match (candidate_id, job_id, score, computed_at)
            SELECT u.c, :jobId, u.s, now()
            FROM unnest(:cids::uuid[], :scores::float8[]) AS u(c, s)
            JOIN candidate cand ON cand.id = u.c
            ON CONFLICT (candidate_id, job_id) DO UPDATE
               SET score = EXCLUDED.score, computed_at = EXCLUDED.computed_at""";

    // Candidate-side variant (Phase 6): one candidate, many jobs. Joined to job so jobs deleted in
    // the meantime are skipped, and to candidate so a deleted candidate writes 0 rows. Rows are
    // inserted in job_id order. No explanation column (V3 or V6) is touched.
    private static final String SQL_UPSERT_FOR_CANDIDATE = """
            INSERT INTO job_match (candidate_id, job_id, score, computed_at)
            SELECT cand.id, u.j, u.s, now()
            FROM unnest(:jids::uuid[], :scores::float8[]) AS u(j, s)
            JOIN job jb ON jb.id = u.j
            JOIN candidate cand ON cand.id = :candidateId
            ORDER BY u.j
            ON CONFLICT (candidate_id, job_id) DO UPDATE
               SET score = EXCLUDED.score, computed_at = EXCLUDED.computed_at""";

    private static final String SQL_RANKED_PAGE = """
            SELECT m.candidate_id, c.full_name, m.score, m.computed_at, m.ai_explanation,
                   c.summary AS candidate_summary, c.updated_at AS candidate_updated_at,
                   m.explanation_payload::text AS explanation_payload, m.explanation_input_hash,
                   m.explanation_model, m.explanation_generated_at
            FROM job_match m JOIN candidate c ON c.id = m.candidate_id
            WHERE m.job_id = :jobId AND m.score >= :minScore
            ORDER BY m.score DESC, c.full_name ASC, c.id ASC
            LIMIT :limit OFFSET :offset""";

    // Guarded explanation write (autocommit, outside any request transaction): it only lands if
    // the candidate, the job (including skill links, via V2 triggers) and the score are exactly
    // as they were read when the prompt was built. Otherwise 0 rows: the result is discarded.
    private static final String SQL_SAVE_EXPLANATION = """
            UPDATE job_match m
               SET ai_explanation = :text,
                   explanation_payload = CAST(:payload AS jsonb),
                   explanation_input_hash = :inputHash,
                   explanation_model = :model,
                   explanation_generated_at = :generatedAt
              FROM candidate c, job j
             WHERE m.job_id = :jobId AND m.candidate_id = :candidateId
               AND c.id = m.candidate_id AND j.id = m.job_id
               AND c.updated_at = :candidateUpdatedAt
               AND j.updated_at = :jobUpdatedAt
               AND m.score = :expectedScore""";

    // Seeker counterpart (V6): same guards, only the seeker_explanation_* columns. The text lives
    // in the payload's 'explanation' key (required by the V6 CHECK).
    private static final String SQL_SAVE_SEEKER_EXPLANATION = """
            UPDATE job_match m
               SET seeker_explanation_payload = CAST(:payload AS jsonb),
                   seeker_explanation_input_hash = :inputHash,
                   seeker_explanation_model = :model,
                   seeker_explanation_generated_at = :generatedAt
              FROM candidate c, job j
             WHERE m.job_id = :jobId AND m.candidate_id = :candidateId
               AND c.id = m.candidate_id AND j.id = m.job_id
               AND c.updated_at = :candidateUpdatedAt
               AND j.updated_at = :jobUpdatedAt
               AND m.score = :expectedScore""";

    private static final String SQL_COUNT_MATCHES = """
            SELECT count(*) FROM job_match m
            WHERE m.job_id = :jobId AND m.score >= :minScore""";

    private static final String SQL_SET_LOCK_TIMEOUT = "SET LOCAL lock_timeout = '" + LOCK_TIMEOUT + "'";

    private static final String SQL_ADVISORY_LOCK =
            "SELECT pg_advisory_xact_lock(hashtextextended(:lockKey, 0))";

    private final NamedParameterJdbcTemplate jdbc;

    public MatchJdbcRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Minimal job header used by the match flow. */
    public record JobHeader(UUID id, String title, String company, String description, Instant updatedAt) {
    }

    /**
     * One row of a ranked match page.
     *
     * @param aiExplanation      raw job_match.ai_explanation (may be stale; see {@code stored})
     * @param candidateUpdatedAt candidate.updated_at, used to guard the explanation write
     * @param stored             the persisted explanation, or null when ai_explanation is null
     */
    public record RankedRow(UUID candidateId, String candidateName, double score, Instant computedAt,
                            String aiExplanation, String candidateSummary, Instant candidateUpdatedAt,
                            StoredExplanation stored) {
    }

    /**
     * A persisted AI explanation (V3 columns).
     *
     * @param text        ai_explanation
     * @param payloadJson explanation_payload as JSON text
     * @param inputHash   sha256 hex of the prompt it was generated from
     * @param model       provider/model label
     * @param generatedAt when it was generated
     */
    public record StoredExplanation(String text, String payloadJson, String inputHash, String model,
                                    Instant generatedAt) {
    }

    public Optional<JobHeader> findJob(UUID jobId) {
        List<JobHeader> rows = jdbc.query(SQL_JOB, new MapSqlParameterSource("jobId", jobId),
                (rs, i) -> new JobHeader(
                        rs.getObject("id", UUID.class),
                        rs.getString("title"),
                        rs.getString("company"),
                        rs.getString("description"),
                        toInstant(rs.getObject("updated_at", OffsetDateTime.class))));
        return rows.stream().findFirst();
    }

    public List<JobRequirement> findRequirements(UUID jobId) {
        return jdbc.query(SQL_REQUIREMENTS, new MapSqlParameterSource("jobId", jobId),
                (rs, i) -> new JobRequirement(
                        rs.getObject("skill_id", UUID.class),
                        rs.getString("name"),
                        rs.getBoolean("required")));
    }

    /** Candidates with no match row for the job, or whose row is older than the candidate/job. */
    public List<UUID> findStaleCandidateIds(UUID jobId) {
        return jdbc.query(SQL_STALE_CANDIDATES, new MapSqlParameterSource("jobId", jobId),
                (rs, i) -> rs.getObject(1, UUID.class));
    }

    public List<UUID> findAllCandidateIds() {
        return jdbc.query(SQL_ALL_CANDIDATES, new MapSqlParameterSource(),
                (rs, i) -> rs.getObject(1, UUID.class));
    }

    public boolean anyCandidateExists() {
        Boolean exists = jdbc.queryForObject(SQL_ANY_CANDIDATE, new MapSqlParameterSource(), Boolean.class);
        return Boolean.TRUE.equals(exists);
    }

    /** Snapshot of all job ids in a stable order (for batch recompute). */
    public List<UUID> findAllJobIds() {
        return jdbc.query(SQL_ALL_JOB_IDS, new MapSqlParameterSource(),
                (rs, i) -> rs.getObject(1, UUID.class));
    }

    /**
     * Candidate skills restricted to the given skills, grouped by candidate.
     *
     * @param skillIds     the job's skill ids
     * @param candidateIds restrict to these candidates, or null for all candidates
     */
    public Map<UUID, List<CandidateSkillFact>> findFacts(Collection<UUID> skillIds,
                                                         Collection<UUID> candidateIds) {
        Map<UUID, List<CandidateSkillFact>> facts = new HashMap<>();
        if (skillIds.isEmpty() || (candidateIds != null && candidateIds.isEmpty())) {
            return facts;
        }
        MapSqlParameterSource params = new MapSqlParameterSource("skillIds", uuidArray(skillIds));
        String sql = SQL_FACTS;
        if (candidateIds != null) {
            params.addValue("candidateIds", uuidArray(candidateIds));
            sql = SQL_FACTS_FOR_CANDIDATES;
        }
        jdbc.query(sql, params, rs -> {
            UUID candidateId = rs.getObject("candidate_id", UUID.class);
            UUID skillId = rs.getObject("skill_id", UUID.class);
            int years = rs.getInt("years_experience");
            Integer yearsOrNull = rs.wasNull() ? null : years;
            facts.computeIfAbsent(candidateId, k -> new ArrayList<>())
                    .add(new CandidateSkillFact(skillId, yearsOrNull));
        });
        return facts;
    }

    /**
     * Inserts or refreshes scores for (candidate, job) pairs; computed_at becomes now().
     *
     * @return number of rows written
     */
    public int upsertScores(UUID jobId, List<UUID> candidateIds, double[] scores) {
        if (candidateIds.size() != scores.length) {
            throw new IllegalArgumentException("candidateIds and scores differ in length");
        }
        int written = 0;
        for (int from = 0; from < candidateIds.size(); from += UPSERT_CHUNK) {
            int to = Math.min(from + UPSERT_CHUNK, candidateIds.size());
            Double[] chunkScores = new Double[to - from];
            for (int i = from; i < to; i++) {
                chunkScores[i - from] = scores[i];
            }
            MapSqlParameterSource params = new MapSqlParameterSource()
                    .addValue("jobId", jobId)
                    .addValue("cids", uuidArray(candidateIds.subList(from, to)))
                    .addValue("scores", sqlArray("float8", chunkScores));
            written += jdbc.update(SQL_UPSERT, params);
        }
        return written;
    }

    /**
     * Candidate-side upsert: inserts or refreshes the scores of one candidate against many jobs;
     * computed_at becomes now(). Jobs deleted in the meantime are skipped; a deleted candidate
     * writes nothing. Explanation columns are never touched.
     *
     * @param jobIds distinct job ids (a duplicate would hit the same row twice and fail the statement)
     * @param scores one score per job id, same order
     * @return number of rows written
     */
    public int upsertCandidateScores(UUID candidateId, List<UUID> jobIds, double[] scores) {
        if (jobIds.size() != scores.length) {
            throw new IllegalArgumentException("jobIds and scores differ in length");
        }
        int written = 0;
        for (int from = 0; from < jobIds.size(); from += UPSERT_CHUNK) {
            int to = Math.min(from + UPSERT_CHUNK, jobIds.size());
            Double[] chunkScores = new Double[to - from];
            for (int i = from; i < to; i++) {
                chunkScores[i - from] = scores[i];
            }
            MapSqlParameterSource params = new MapSqlParameterSource()
                    .addValue("candidateId", candidateId)
                    .addValue("jids", uuidArray(jobIds.subList(from, to)))
                    .addValue("scores", sqlArray("float8", chunkScores));
            written += jdbc.update(SQL_UPSERT_FOR_CANDIDATE, params);
        }
        return written;
    }

    public List<RankedRow> findRankedPage(UUID jobId, double minScore, int limit, long offset) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("jobId", jobId)
                .addValue("minScore", minScore)
                .addValue("limit", limit)
                .addValue("offset", offset);
        return jdbc.query(SQL_RANKED_PAGE, params, (rs, i) -> {
            String aiExplanation = rs.getString("ai_explanation");
            StoredExplanation stored = aiExplanation == null ? null : new StoredExplanation(
                    aiExplanation,
                    rs.getString("explanation_payload"),
                    rs.getString("explanation_input_hash"),
                    rs.getString("explanation_model"),
                    toInstant(rs.getObject("explanation_generated_at", OffsetDateTime.class)));
            return new RankedRow(
                    rs.getObject("candidate_id", UUID.class),
                    rs.getString("full_name"),
                    rs.getDouble("score"),
                    toInstant(rs.getObject("computed_at", OffsetDateTime.class)),
                    aiExplanation,
                    rs.getString("candidate_summary"),
                    toInstant(rs.getObject("candidate_updated_at", OffsetDateTime.class)),
                    stored);
        });
    }

    /**
     * Stores a generated recruiter explanation (V3 columns). Same as
     * {@link #saveExplanation(Audience, UUID, UUID, double, Instant, Instant, String, String, String, String, Instant)}
     * with {@link Audience#RECRUITER}.
     */
    public int saveExplanation(UUID jobId, UUID candidateId, double expectedScore, Instant candidateUpdatedAt,
                               Instant jobUpdatedAt, String text, String payloadJson, String inputHash,
                               String model, Instant generatedAt) {
        return saveExplanation(Audience.RECRUITER, jobId, candidateId, expectedScore, candidateUpdatedAt,
                jobUpdatedAt, text, payloadJson, inputHash, model, generatedAt);
    }

    /**
     * Stores a generated explanation for one audience if nothing it was based on has changed. Call
     * outside any transaction: it autocommits and is one short statement. RECRUITER writes only the
     * V3 columns, SEEKER only the V6 {@code seeker_explanation_*} columns.
     *
     * @param expectedScore the raw job_match.score read with the page (not the rounded value)
     * @param text          the explanation text; stored in {@code ai_explanation} for RECRUITER, unused
     *                      for SEEKER (the payload's {@code explanation} key is the text)
     * @return 1 if saved, 0 if the candidate, job or score changed since they were read (result discarded)
     */
    public int saveExplanation(Audience audience, UUID jobId, UUID candidateId, double expectedScore,
                               Instant candidateUpdatedAt, Instant jobUpdatedAt, String text, String payloadJson,
                               String inputHash, String model, Instant generatedAt) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("jobId", jobId)
                .addValue("candidateId", candidateId)
                .addValue("expectedScore", expectedScore)
                .addValue("candidateUpdatedAt", toOffset(candidateUpdatedAt), Types.TIMESTAMP_WITH_TIMEZONE)
                .addValue("jobUpdatedAt", toOffset(jobUpdatedAt), Types.TIMESTAMP_WITH_TIMEZONE)
                .addValue("payload", payloadJson)
                .addValue("inputHash", inputHash)
                .addValue("model", model)
                .addValue("generatedAt", toOffset(generatedAt), Types.TIMESTAMP_WITH_TIMEZONE);
        String sql = switch (audience) {
            case RECRUITER -> {
                params.addValue("text", text);
                yield SQL_SAVE_EXPLANATION;
            }
            case SEEKER -> SQL_SAVE_SEEKER_EXPLANATION;
        };
        return jdbc.update(sql, params);
    }

    public long countMatches(UUID jobId, double minScore) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("jobId", jobId)
                .addValue("minScore", minScore);
        Long count = jdbc.queryForObject(SQL_COUNT_MATCHES, params, Long.class);
        return count == null ? 0L : count;
    }

    /**
     * Serializes match writes for one job until the current transaction ends. Must run inside
     * a transaction. Waits at most {@value #LOCK_TIMEOUT}.
     *
     * @throws CannotAcquireLockException if the lock could not be acquired in time
     */
    public void lockJob(UUID jobId) {
        setLockTimeout();
        acquireJobLock(jobId);
    }

    /**
     * Takes the per-job advisory lock ({@link #lockJob}) for every job, in the given order, until the
     * current transaction ends. Sets {@code lock_timeout} once; each lock waits at most
     * {@value #LOCK_TIMEOUT}. Must run inside a transaction. Callers lock one chunk per transaction
     * (talentmatch.matches.rescore-chunk) to stay well under max_locks_per_transaction.
     *
     * <p>The ids must be strictly ascending in PostgreSQL uuid order ({@link UuidOrder}): every
     * multi-job locker uses that order, and single-job lockers hold one lock at a time, so no lock
     * cycle can form.
     *
     * @throws IllegalArgumentException   if the ids are not strictly ascending in uuid order
     * @throws CannotAcquireLockException if a lock could not be acquired in time
     * @throws PessimisticLockingFailureException on a deadlock (backstop; should not happen)
     */
    public void lockJobs(List<UUID> sortedJobIds) {
        if (sortedJobIds.isEmpty()) {
            return;
        }
        if (!UuidOrder.isStrictlyAscending(sortedJobIds)) {
            throw new IllegalArgumentException("job ids must be distinct and in PostgreSQL uuid order");
        }
        setLockTimeout();
        for (UUID jobId : sortedJobIds) {
            acquireJobLock(jobId);
        }
    }

    private void setLockTimeout() {
        jdbc.getJdbcOperations().execute(SQL_SET_LOCK_TIMEOUT);
    }

    private void acquireJobLock(UUID jobId) {
        try {
            jdbc.query(SQL_ADVISORY_LOCK, new MapSqlParameterSource("lockKey", "job_match:" + jobId),
                    (ResultSetExtractor<Void>) rs -> null);
        } catch (PessimisticLockingFailureException e) {
            throw e;
        } catch (DataAccessException e) {
            if ("55P03".equals(sqlState(e))) {
                throw new CannotAcquireLockException("Timed out waiting for the match lock of job " + jobId, e);
            }
            throw e;
        }
    }

    private static String sqlState(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c instanceof SQLException sql && sql.getSQLState() != null) {
                return sql.getSQLState();
            }
            if (c.getCause() == c) {
                break;
            }
        }
        return null;
    }

    private static Instant toInstant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    /** PgJDBC does not bind Instant; timestamptz needs an OffsetDateTime. */
    private static OffsetDateTime toOffset(Instant value) {
        return value == null ? null : OffsetDateTime.ofInstant(value, ZoneOffset.UTC);
    }

    private static SqlTypeValue uuidArray(Collection<UUID> ids) {
        return sqlArray("uuid", ids.toArray(new UUID[0]));
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
