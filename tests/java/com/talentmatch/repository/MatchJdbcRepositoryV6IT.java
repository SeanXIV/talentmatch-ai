package com.talentmatch.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.talentmatch.ai.Audience;
import com.talentmatch.support.AbstractApiIT;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Phase 6 step 1 (spec §3, §4.2, §9.2.1): the V6 seeker explanation columns, the audience-aware
 * guarded write, the candidate-side score upsert and the multi-job advisory lock.
 */
class MatchJdbcRepositoryV6IT extends AbstractApiIT {

    private static final String H_RECRUITER = "a".repeat(64);
    private static final String H_SEEKER = "b".repeat(64);
    private static final String SEEKER_PAYLOAD =
            "{\"headline\":\"Strong fit\",\"explanation\":\"You have Java.\",\"strengths\":[],\"gaps\":[]}";
    private static final String RECRUITER_PAYLOAD = "{\"headline\":\"Recruiter view\"}";
    private static final Instant GENERATED = Instant.parse("2026-10-18T10:00:00Z");

    /** Test-local audience; mapped in {@link #save} only, so the step-2 ExplanationTarget switch is one line. */
    enum Side { RECRUITER, SEEKER }

    @Autowired
    private MatchJdbcRepository repo;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private UUID candidateId;
    private UUID jobId;

    @BeforeEach
    void pair() {
        skills("Java", "SQL");
        candidateId = candidate("Ada Seeker", "ada.seeker@example.com", "Java:5", "SQL");
        jobId = job("Backend Engineer", "Acme", "Java", "~SQL");
    }

    // ------------------------------------------------------------------ helpers

    /** The only call site of the audience overload of saveExplanation. */
    private int save(Side side, UUID job, UUID cand, double expectedScore, Instant candUpdated, Instant jobUpdated,
                     String text, String payload, String hash, String model) {
        return repo.saveExplanation(Audience.valueOf(side.name()), job, cand, expectedScore, candUpdated, jobUpdated,
                text, payload, hash, model, GENERATED);
    }

    private int saveCurrent(Side side, String text, String payload, String hash, String model) {
        return save(side, jobId, candidateId, score(), updatedAt("candidate", candidateId), updatedAt("job", jobId),
                text, payload, hash, model);
    }

    private void insertMatch(UUID cand, UUID job, double score) {
        jdbc.update("INSERT INTO job_match (candidate_id, job_id, score) VALUES (?, ?, ?)", cand, job, score);
    }

    private double score() {
        return jdbc.queryForObject("SELECT score FROM job_match WHERE candidate_id = ? AND job_id = ?",
                Double.class, candidateId, jobId);
    }

    private Map<String, Object> row() {
        return jdbc.queryForMap("""
                SELECT score, computed_at, ai_explanation, explanation_payload::text AS explanation_payload,
                       explanation_input_hash, explanation_model, explanation_generated_at,
                       seeker_explanation_payload::text AS seeker_explanation_payload,
                       seeker_explanation_input_hash, seeker_explanation_model, seeker_explanation_generated_at
                FROM job_match WHERE candidate_id = ? AND job_id = ?""", candidateId, jobId);
    }

    private static void assertRecruiterNull(Map<String, Object> r) {
        assertThat(r.get("ai_explanation")).isNull();
        assertThat(r.get("explanation_payload")).isNull();
        assertThat(r.get("explanation_input_hash")).isNull();
        assertThat(r.get("explanation_model")).isNull();
        assertThat(r.get("explanation_generated_at")).isNull();
    }

    private static void assertSeekerNull(Map<String, Object> r) {
        assertThat(r.get("seeker_explanation_payload")).isNull();
        assertThat(r.get("seeker_explanation_input_hash")).isNull();
        assertThat(r.get("seeker_explanation_model")).isNull();
        assertThat(r.get("seeker_explanation_generated_at")).isNull();
    }

    private static void assertSeeker(Map<String, Object> r, String hash, String model) {
        assertThat(r.get("seeker_explanation_payload").toString()).contains("\"explanation\": \"You have Java.\"");
        assertThat(r.get("seeker_explanation_input_hash")).isEqualTo(hash);
        assertThat(r.get("seeker_explanation_model")).isEqualTo(model);
        assertThat(((Timestamp) r.get("seeker_explanation_generated_at")).toInstant()).isEqualTo(GENERATED);
    }

    private static void assertRecruiter(Map<String, Object> r, String text, String hash, String model) {
        assertThat(r.get("ai_explanation")).isEqualTo(text);
        assertThat(r.get("explanation_payload").toString()).contains("Recruiter view");
        assertThat(r.get("explanation_input_hash")).isEqualTo(hash);
        assertThat(r.get("explanation_model")).isEqualTo(model);
        assertThat(((Timestamp) r.get("explanation_generated_at")).toInstant()).isEqualTo(GENERATED);
    }

    /**
     * An IllegalArgumentException from the repository. Called through the {@code @Repository} bean,
     * Spring's persistence exception translation wraps it in InvalidDataAccessApiUsageException.
     */
    private static void assertIllegalArgument(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call).satisfiesAnyOf(
                t -> assertThat(t).isInstanceOf(IllegalArgumentException.class),
                t -> assertThat(t).isInstanceOf(InvalidDataAccessApiUsageException.class)
                        .cause().isInstanceOf(IllegalArgumentException.class));
    }

    private static String lockKey(UUID jobId) {
        return "job_match:" + jobId;
    }

    /** Tries the match lock of a job on its own session; releases it again if it got it. */
    private boolean lockIsFree(UUID job) throws SQLException {
        try (Connection c = rawConnection();
             var ps = c.prepareStatement("SELECT pg_try_advisory_lock(hashtextextended(?, 0))")) {
            ps.setString(1, lockKey(job));
            try (var rs = ps.executeQuery()) {
                rs.next();
                return rs.getBoolean(1);        // closing the session releases it
            }
        }
    }

    private List<UUID> sqlJobs(int n, String prefix) {
        return jdbc.queryForList("""
                INSERT INTO job (title, company) SELECT ? || g, 'Bulk Corp' FROM generate_series(1, ?) g
                RETURNING id""", UUID.class, prefix, n);
    }

    // ------------------------------------------------------------------ schema V6

    @Test
    void seekerColumnsExistAndAreNullable() {
        List<Map<String, Object>> cols = jdbc.queryForList("""
                SELECT column_name, data_type, character_maximum_length, is_nullable
                FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = 'job_match'
                  AND column_name LIKE 'seeker\\_%'
                ORDER BY column_name""");
        assertThat(cols).extracting(c -> c.get("column_name")).containsExactly(
                "seeker_explanation_generated_at", "seeker_explanation_input_hash",
                "seeker_explanation_model", "seeker_explanation_payload");
        assertThat(cols).extracting(c -> c.get("data_type")).containsExactly(
                "timestamp with time zone", "character", "character varying", "jsonb");
        assertThat(cols).extracting(c -> c.get("character_maximum_length")).containsExactly(null, 64, 200, null);
        assertThat(cols).allSatisfy(c -> assertThat(c.get("is_nullable")).isEqualTo("YES"));
    }

    @Test
    void checkConstraintAndIndexExist() {
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM pg_constraint WHERE conname = 'ck_job_match_seeker_explanation_complete'
                  AND contype = 'c' AND convalidated AND conrelid = 'public.job_match'::regclass""",
                Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT indexdef FROM pg_indexes
                WHERE schemaname = 'public' AND tablename = 'job_match' AND indexname = 'ix_job_match_candidate_score'
                """, String.class)).contains("(candidate_id, score DESC)");
        assertThat(jdbc.queryForObject(
                "SELECT success FROM flyway_schema_history WHERE version = '6'", Boolean.class)).isTrue();
    }

    @Test
    void checkRejectsPartialOrMalformedSeekerExplanations() {
        insertMatch(candidateId, jobId, 0.5);
        String set = "UPDATE job_match SET seeker_explanation_payload = CAST(? AS jsonb), "
                + "seeker_explanation_input_hash = ?, seeker_explanation_model = ?, "
                + "seeker_explanation_generated_at = ? WHERE candidate_id = ? AND job_id = ?";
        Timestamp now = Timestamp.from(Instant.now());
        Object[][] bad = {
                {SEEKER_PAYLOAD, null, null, null},                       // payload only
                {SEEKER_PAYLOAD, H_SEEKER, "ollama/qa", null},            // no generated_at
                {SEEKER_PAYLOAD, H_SEEKER, null, now},                    // no model
                {SEEKER_PAYLOAD, H_SEEKER, "   ", now},                   // blank model
                {SEEKER_PAYLOAD, null, "ollama/qa", now},                 // no hash
                {null, H_SEEKER, "ollama/qa", now},                       // no payload
                {"{\"headline\":\"h\"}", H_SEEKER, "ollama/qa", now},     // payload without explanation
                {"[\"explanation\"]", H_SEEKER, "ollama/qa", now},        // not an object
                {SEEKER_PAYLOAD, "B".repeat(64), "ollama/qa", now},       // upper-case hash
                {SEEKER_PAYLOAD, "abc", "ollama/qa", now},                // short hash
        };
        for (Object[] b : bad) {
            assertThatThrownBy(() -> jdbc.update(set, b[0], b[1], b[2], b[3], candidateId, jobId))
                    .as("partial seeker explanation %s", java.util.Arrays.toString(b))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("ck_job_match_seeker_explanation_complete");
        }
        // complete is accepted, and clearing it all at once too
        jdbc.update(set, SEEKER_PAYLOAD, H_SEEKER, "ollama/qa", now, candidateId, jobId);
        jdbc.update(set, null, null, null, null, candidateId, jobId);
        assertSeekerNull(row());
    }

    // ------------------------------------------------------------------ guarded explanation writes


    @Test
    void seekerWritesOnlyTheSeekerColumns() {
        insertMatch(candidateId, jobId, 0.75);
        assertThat(saveCurrent(Side.SEEKER, "ignored text", SEEKER_PAYLOAD, H_SEEKER, "ollama/seeker"))
                .isEqualTo(1);
        Map<String, Object> r = row();
        assertSeeker(r, H_SEEKER, "ollama/seeker");
        assertRecruiterNull(r);         // the text argument is not stored in ai_explanation for SEEKER
    }

    @Test
    void recruiterWritesOnlyTheRecruiterColumns() {
        insertMatch(candidateId, jobId, 0.75);
        assertThat(saveCurrent(Side.RECRUITER, "Recruiter text", RECRUITER_PAYLOAD, H_RECRUITER, "ollama/r"))
                .isEqualTo(1);
        Map<String, Object> r = row();
        assertRecruiter(r, "Recruiter text", H_RECRUITER, "ollama/r");
        assertSeekerNull(r);
    }

    @Test
    void recruiterWriteLeavesAnExistingSeekerExplanationAlone() {
        insertMatch(candidateId, jobId, 0.75);
        saveCurrent(Side.SEEKER, null, SEEKER_PAYLOAD, H_SEEKER, "ollama/seeker");
        assertThat(saveCurrent(Side.RECRUITER, "Recruiter text", RECRUITER_PAYLOAD, H_RECRUITER, "ollama/r"))
                .isEqualTo(1);
        Map<String, Object> r = row();
        assertSeeker(r, H_SEEKER, "ollama/seeker");
        assertRecruiter(r, "Recruiter text", H_RECRUITER, "ollama/r");
    }

    @Test
    void seekerWriteLeavesAnExistingRecruiterExplanationAlone() {
        insertMatch(candidateId, jobId, 0.75);
        saveCurrent(Side.RECRUITER, "Recruiter text", RECRUITER_PAYLOAD, H_RECRUITER, "ollama/r");
        assertThat(saveCurrent(Side.SEEKER, "other text", SEEKER_PAYLOAD, H_SEEKER, "ollama/seeker"))
                .isEqualTo(1);
        Map<String, Object> r = row();
        assertRecruiter(r, "Recruiter text", H_RECRUITER, "ollama/r");
        assertSeeker(r, H_SEEKER, "ollama/seeker");
    }

    @Test
    void legacySignatureIsTheRecruiterWrite() {
        insertMatch(candidateId, jobId, 0.75);
        saveCurrent(Side.SEEKER, null, SEEKER_PAYLOAD, H_SEEKER, "ollama/seeker");
        int written = repo.saveExplanation(jobId, candidateId, score(), updatedAt("candidate", candidateId),
                updatedAt("job", jobId), "Recruiter text", RECRUITER_PAYLOAD, H_RECRUITER, "ollama/r", GENERATED);
        assertThat(written).isEqualTo(1);
        Map<String, Object> r = row();
        assertRecruiter(r, "Recruiter text", H_RECRUITER, "ollama/r");
        assertSeeker(r, H_SEEKER, "ollama/seeker");
    }

    @Test
    void staleCandidateJobOrScoreIsRejectedForBothAudiences() {
        insertMatch(candidateId, jobId, 0.75);
        Instant cand = updatedAt("candidate", candidateId);
        Instant job = updatedAt("job", jobId);
        double s = score();
        for (Side side : Side.values()) {
            String payload = side == Side.SEEKER ? SEEKER_PAYLOAD : RECRUITER_PAYLOAD;
            assertThat(save(side, jobId, candidateId, s, cand.minus(1, ChronoUnit.MICROS), job,
                    "t", payload, H_SEEKER, "m")).as("%s stale candidate", side).isZero();
            assertThat(save(side, jobId, candidateId, s, cand, job.minus(1, ChronoUnit.MICROS),
                    "t", payload, H_SEEKER, "m")).as("%s stale job", side).isZero();
            assertThat(save(side, jobId, candidateId, s + 0.01, cand, job,
                    "t", payload, H_SEEKER, "m")).as("%s changed score", side).isZero();
            assertThat(save(side, jobId, UUID.randomUUID(), s, cand, job,
                    "t", payload, H_SEEKER, "m")).as("%s no row", side).isZero();
        }
        Map<String, Object> r = row();
        assertSeekerNull(r);
        assertRecruiterNull(r);
    }

    @Test
    void seekerWriteDiscardedAfterACandidateEdit() {
        insertMatch(candidateId, jobId, 0.75);
        Instant cand = updatedAt("candidate", candidateId);
        Instant job = updatedAt("job", jobId);
        double s = score();
        // a real profile edit between reading and saving (bumps candidate.updated_at)
        jdbc.update("UPDATE candidate SET summary = 'changed' WHERE id = ?", candidateId);
        assertThat(updatedAt("candidate", candidateId)).isAfter(cand);
        assertThat(save(Side.SEEKER, jobId, candidateId, s, cand, job, null, SEEKER_PAYLOAD, H_SEEKER, "m"))
                .isZero();
        assertSeekerNull(row());
    }

    @Test
    void seekerWriteDiscardedAfterAJobSkillChange() {
        insertMatch(candidateId, jobId, 0.75);
        Instant cand = updatedAt("candidate", candidateId);
        Instant job = updatedAt("job", jobId);
        double s = score();
        jdbc.update("DELETE FROM job_skill WHERE job_id = ? AND NOT required", jobId);   // V2 trigger bumps job
        assertThat(save(Side.SEEKER, jobId, candidateId, s, cand, job, null, SEEKER_PAYLOAD, H_SEEKER, "m"))
                .isZero();
        assertSeekerNull(row());
    }

    @Test
    void seekerPayloadWithoutExplanationKeyViolatesTheCheck() {
        insertMatch(candidateId, jobId, 0.75);
        assertThatThrownBy(() -> saveCurrent(Side.SEEKER, "text", "{\"headline\":\"h\"}", H_SEEKER, "m"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertSeekerNull(row());
    }

    // ------------------------------------------------------------------ candidate-side score upsert

    @Test
    void insertsOneRowPerJobWithTheGivenScores() {
        UUID job2 = job("Data Engineer", "Acme", "SQL");
        UUID job3 = job("Platform Engineer", "Acme", "Java", "SQL");
        Instant before = jdbc.queryForObject("SELECT now()", Timestamp.class).toInstant();
        // input not in uuid order: the statement sorts
        List<UUID> jobs = List.of(job3, jobId, job2);
        int written = repo.upsertCandidateScores(candidateId, jobs, new double[] {0.3, 0.9, 0.1});
        assertThat(written).isEqualTo(3);
        Map<UUID, Double> scores = new java.util.HashMap<>();
        jdbc.query("SELECT job_id, score, computed_at FROM job_match WHERE candidate_id = ?", rs -> {
            scores.put(rs.getObject("job_id", UUID.class), rs.getDouble("score"));
            assertThat(rs.getTimestamp("computed_at").toInstant()).isAfterOrEqualTo(before);
        }, candidateId);
        assertThat(scores).containsExactlyInAnyOrderEntriesOf(Map.of(job3, 0.3, jobId, 0.9, job2, 0.1));
    }

    @Test
    void updatesScoreAndComputedAtButNeverTheExplanations() {
        insertMatch(candidateId, jobId, 0.75);
        saveCurrent(Side.RECRUITER, "Recruiter text", RECRUITER_PAYLOAD, H_RECRUITER, "ollama/r");
        saveCurrent(Side.SEEKER, null, SEEKER_PAYLOAD, H_SEEKER, "ollama/seeker");
        jdbc.update("UPDATE job_match SET computed_at = now() - interval '1 day' WHERE job_id = ?", jobId);
        Instant oldComputed = ((Timestamp) row().get("computed_at")).toInstant();

        assertThat(repo.upsertCandidateScores(candidateId, List.of(jobId), new double[] {0.25})).isEqualTo(1);

        Map<String, Object> r = row();
        assertThat((Double) r.get("score")).isEqualTo(0.25);
        assertThat(((Timestamp) r.get("computed_at")).toInstant()).isAfter(oldComputed);
        assertRecruiter(r, "Recruiter text", H_RECRUITER, "ollama/r");
        assertSeeker(r, H_SEEKER, "ollama/seeker");
    }

    @Test
    void jobSideUpsertAlsoLeavesTheSeekerColumnsAlone() {
        insertMatch(candidateId, jobId, 0.75);
        saveCurrent(Side.SEEKER, null, SEEKER_PAYLOAD, H_SEEKER, "ollama/seeker");
        assertThat(repo.upsertScores(jobId, List.of(candidateId), new double[] {0.5})).isEqualTo(1);
        Map<String, Object> r = row();
        assertThat((Double) r.get("score")).isEqualTo(0.5);
        assertSeeker(r, H_SEEKER, "ollama/seeker");
        assertRecruiterNull(r);
    }

    @Test
    void skipsJobsThatNoLongerExist() {
        UUID gone = job("Deleted Engineer", "Acme", "Java");
        jdbc.update("DELETE FROM job WHERE id = ?", gone);
        UUID neverExisted = UUID.randomUUID();
        int written = repo.upsertCandidateScores(candidateId, List.of(gone, jobId, neverExisted),
                new double[] {0.1, 0.2, 0.3});
        assertThat(written).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT job_id FROM job_match WHERE candidate_id = ?", UUID.class,
                candidateId)).containsExactly(jobId);
    }

    @Test
    void deletedCandidateWritesNothing() {
        UUID gone = candidate("Gone Seeker", "gone@example.com", "Java");
        jdbc.update("DELETE FROM candidate WHERE id = ?", gone);
        assertThat(repo.upsertCandidateScores(gone, List.of(jobId), new double[] {0.5})).isZero();
        assertThat(repo.upsertCandidateScores(UUID.randomUUID(), List.of(jobId), new double[] {0.5})).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM job_match", Integer.class)).isZero();
    }

    @Test
    void emptyInputWritesNothing() {
        assertThat(repo.upsertCandidateScores(candidateId, List.of(), new double[0])).isZero();
    }

    @Test
    void lengthMismatchIsRejected() {
        assertIllegalArgument(() -> repo.upsertCandidateScores(candidateId, List.of(jobId), new double[] {0.1, 0.2}));
        assertIllegalArgument(() -> repo.upsertCandidateScores(candidateId, List.of(jobId), new double[0]));
    }

    @Test
    void moreThanOneStatementChunk() {
        int n = MatchJdbcRepository.UPSERT_CHUNK + 7;
        List<UUID> jobs = new ArrayList<>(sqlJobs(n, "Bulk "));
        Collections.shuffle(jobs, new Random(3));
        double[] scores = new double[n];
        for (int i = 0; i < n; i++) {
            scores[i] = (i % 100) / 100.0;
        }
        assertThat(repo.upsertCandidateScores(candidateId, jobs, scores)).isEqualTo(n);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM job_match WHERE candidate_id = ?", Integer.class,
                candidateId)).isEqualTo(n);
        // spot-check the pairing across the chunk boundary
        for (int i : new int[] {0, MatchJdbcRepository.UPSERT_CHUNK - 1, MatchJdbcRepository.UPSERT_CHUNK, n - 1}) {
            assertThat(jdbc.queryForObject("SELECT score FROM job_match WHERE candidate_id = ? AND job_id = ?",
                    Double.class, candidateId, jobs.get(i))).as("row %d", i).isEqualTo(scores[i]);
        }
        // a second pass refreshes rather than duplicating
        assertThat(repo.upsertCandidateScores(candidateId, jobs, scores)).isEqualTo(n);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM job_match", Integer.class)).isEqualTo(n);
    }

    // ------------------------------------------------------------------ multi-job advisory locks

    private TransactionTemplate tx() {
        return new TransactionTemplate(transactionManager);
    }

    @Test
    void emptyListIsANoOp() {
        repo.lockJobs(List.of());                       // outside a transaction: nothing is executed
        String timeout = tx().execute(s -> {
            repo.lockJobs(List.of());
            return jdbc.queryForObject("SHOW lock_timeout", String.class);
        });
        assertThat(timeout).as("no SET LOCAL for an empty list").isEqualTo("0");
    }

    @Test
    void rejectsUnsortedOrDuplicateIdsBeforeTakingAnyLock() throws SQLException {
        List<UUID> sorted = UuidOrder.sortedDistinct(List.of(UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID()));
        List<UUID> reversed = new ArrayList<>(sorted);
        Collections.reverse(reversed);
        List<UUID> dup = List.of(sorted.get(0), sorted.get(0));
        UUID low = UUID.fromString("7fffffff-ffff-ffff-ffff-ffffffffffff");
        UUID high = UUID.fromString("80000000-0000-0000-0000-000000000000");
        List<UUID> signedOrder = List.of(high, low);    // ascending for UUID.compareTo, not for PostgreSQL
        for (List<UUID> bad : List.of(reversed, dup, signedOrder)) {
            Long held = tx().execute(s -> {
                assertIllegalArgument(() -> repo.lockJobs(bad));
                return jdbc.queryForObject("SELECT count(*) FROM pg_locks WHERE locktype = 'advisory' "
                        + "AND pid = pg_backend_pid()", Long.class);
            });
            assertThat(held).as("no lock taken for %s", bad).isZero();
        }
        for (UUID id : sorted) {
            assertThat(lockIsFree(id)).isTrue();
        }
    }

    @Test
    void holdsEveryLockUntilTheTransactionEnds() {
        List<UUID> ids = UuidOrder.sortedDistinct(List.of(UUID.randomUUID(), UUID.randomUUID(),
                UUID.fromString("80000000-0000-0000-0000-000000000001"), UUID.randomUUID()));
        tx().executeWithoutResult(s -> {
            repo.lockJobs(ids);
            assertThat(jdbc.queryForObject("SHOW lock_timeout", String.class)).isEqualTo("10s");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_locks WHERE locktype = 'advisory' "
                    + "AND pid = pg_backend_pid() AND granted", Long.class)).isEqualTo(ids.size());
            for (UUID id : ids) {
                try {
                    assertThat(lockIsFree(id)).as("lock of %s held by the transaction", id).isFalse();
                } catch (SQLException e) {
                    throw new IllegalStateException(e);
                }
            }
        });
        for (UUID id : ids) {
            try {
                assertThat(lockIsFree(id)).as("lock of %s released at commit", id).isTrue();
            } catch (SQLException e) {
                throw new IllegalStateException(e);
            }
        }
        // the transaction-local timeout does not leak into the pooled connection
        assertThat(jdbc.queryForObject("SHOW lock_timeout", String.class)).isEqualTo("0");
    }

    @Test
    void sameKeyAsLockJob() throws Exception {
        UUID id = UUID.randomUUID();
        try (Connection holder = rawConnection()) {
            holdMatchLock(holder, id);              // the helper existing ITs use for lockJob
            assertThat(lockIsFree(id)).isFalse();
        }
        tx().executeWithoutResult(s -> {
            repo.lockJob(id);
            try {
                assertThat(lockIsFree(id)).as("lockJob and lockJobs share the key").isFalse();
            } catch (SQLException e) {
                throw new IllegalStateException(e);
            }
        });
    }

    @Test
    void locksInOrderAndTimesOutAsCannotAcquireLock() throws Exception {
        List<UUID> ids = UuidOrder.sortedDistinct(List.of(UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID()));
        UUID first = ids.get(0);
        UUID blocked = ids.get(1);
        UUID last = ids.get(2);
        try (Connection holder = rawConnection()) {
            holdMatchLock(holder, blocked);
            long start = System.nanoTime();
            CompletableFuture<Void> locker = CompletableFuture.runAsync(
                    () -> tx().executeWithoutResult(s -> repo.lockJobs(ids)));

            // wait until the locker is waiting on the advisory lock
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (jdbc.queryForObject("SELECT count(*) FROM pg_locks WHERE locktype = 'advisory' AND NOT granted",
                    Long.class) == 0) {
                assertThat(System.nanoTime()).as("locker never blocked").isLessThan(deadline);
                Thread.sleep(50);
            }
            assertThat(lockIsFree(first)).as("locks before the blocked one are held").isFalse();
            assertThat(lockIsFree(last)).as("locks after the blocked one are not yet taken").isTrue();

            assertThatThrownBy(() -> locker.get(30, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .cause()
                    .isInstanceOf(CannotAcquireLockException.class)
                    .isInstanceOf(PessimisticLockingFailureException.class);
            Duration waited = Duration.ofNanos(System.nanoTime() - start);
            assertThat(waited).as("waited for lock_timeout (10s)").isBetween(Duration.ofSeconds(9),
                    Duration.ofSeconds(25));
            assertThat(lockIsFree(first)).as("rolled back: first lock released").isTrue();
        }
    }
}
