package com.talentmatch.repository;

import com.talentmatch.domain.scoring.CandidateSkillFact;
import com.talentmatch.domain.scoring.JobRequirement;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
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
            SELECT id, title, company, updated_at FROM job WHERE id = :jobId""";

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
    // ai_explanation is never touched.
    private static final String SQL_UPSERT = """
            INSERT INTO job_match (candidate_id, job_id, score, computed_at)
            SELECT u.c, :jobId, u.s, now()
            FROM unnest(:cids::uuid[], :scores::float8[]) AS u(c, s)
            JOIN candidate cand ON cand.id = u.c
            ON CONFLICT (candidate_id, job_id) DO UPDATE
               SET score = EXCLUDED.score, computed_at = EXCLUDED.computed_at""";

    private static final String SQL_RANKED_PAGE = """
            SELECT m.candidate_id, c.full_name, m.score, m.computed_at, m.ai_explanation
            FROM job_match m JOIN candidate c ON c.id = m.candidate_id
            WHERE m.job_id = :jobId AND m.score >= :minScore
            ORDER BY m.score DESC, c.full_name ASC, c.id ASC
            LIMIT :limit OFFSET :offset""";

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
    public record JobHeader(UUID id, String title, String company, Instant updatedAt) {
    }

    /** One row of a ranked match page. */
    public record RankedRow(UUID candidateId, String candidateName, double score, Instant computedAt,
                            String aiExplanation) {
    }

    public Optional<JobHeader> findJob(UUID jobId) {
        List<JobHeader> rows = jdbc.query(SQL_JOB, new MapSqlParameterSource("jobId", jobId),
                (rs, i) -> new JobHeader(
                        rs.getObject("id", UUID.class),
                        rs.getString("title"),
                        rs.getString("company"),
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

    public List<RankedRow> findRankedPage(UUID jobId, double minScore, int limit, long offset) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("jobId", jobId)
                .addValue("minScore", minScore)
                .addValue("limit", limit)
                .addValue("offset", offset);
        return jdbc.query(SQL_RANKED_PAGE, params, (rs, i) -> new RankedRow(
                rs.getObject("candidate_id", UUID.class),
                rs.getString("full_name"),
                rs.getDouble("score"),
                toInstant(rs.getObject("computed_at", OffsetDateTime.class)),
                rs.getString("ai_explanation")));
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
        try {
            jdbc.getJdbcOperations().execute(SQL_SET_LOCK_TIMEOUT);
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
