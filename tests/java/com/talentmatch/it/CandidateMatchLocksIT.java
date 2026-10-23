package com.talentmatch.it;

import static com.talentmatch.support.SeekerFixtures.holdCandidateLock;
import static com.talentmatch.support.SeekerFixtures.holdJobLock;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.talentmatch.support.AbstractApiIT;
import com.talentmatch.support.Api.Res;
import java.sql.Connection;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Phase 6 §12.3 (step 4 locking fixes) and §9.2 item 7: job and candidate edits take the match
 * advisory locks (503 SERVICE_BUSY when held), the candidate matches endpoint maps lock timeouts to
 * 503 MATCHES_BUSY. Each busy case waits for the 10s lock_timeout.
 */
class CandidateMatchLocksIT extends AbstractApiIT {

    private static final String SERVICE_BUSY = "The server is busy with a conflicting update. Please try again in a few "
            + "seconds.";

    private UUID cand;

    @BeforeEach
    void data() {
        skills("Java", "SQL", "Go");
        cand = candidate("Ada Lovelace", "ada@example.com", "Java:5", "SQL");
    }

    private Res candidateMatches(UUID candidateId) {
        return api.get("/api/candidates/" + candidateId + "/matches");
    }

    private ObjectNode profileBody(String headline) {
        ObjectNode p = mapper.createObjectNode();
        p.put("fullName", "Olive Owner");
        p.put("email", "olive@example.com");
        p.put("headline", headline);
        ArrayNode skills = p.putArray("skills");
        skills.addObject().put("name", "Java").put("years", 5);
        skills.addObject().put("name", "SQL");
        ObjectNode body = mapper.createObjectNode();
        body.set("profile", p);
        return body;
    }

    private static void assertServiceBusy(Res r) {
        JsonNode err = assertError(r, 503, "SERVICE_BUSY");
        assertThat(r.header("Retry-After")).isEqualTo("2");
        assertThat(err.get("message").asText()).isEqualTo(SERVICE_BUSY);
    }

    private static void assertMatchesBusy(Res r) {
        assertError(r, 503, "MATCHES_BUSY");
        assertThat(r.header("Retry-After")).isEqualTo("2");
    }

    private int rows(UUID jobId) {
        return jdbc.queryForObject("SELECT count(*) FROM job_match WHERE job_id = ? AND candidate_id = ?", Integer.class,
                jobId, cand);
    }

    // ------------------------------------------------------------------ §12.3: job edits

    @Test
    void heldJobLockMakesJobUpdateServiceBusyButNotCreateOrDelete() throws Exception {
        UUID job = job("Backend", "Acme", "Java");
        UUID other = job("Other", "Acme", "SQL");
        try (Connection c = rawConnection()) {
            holdJobLock(c, job);
            long t0 = System.nanoTime();
            Res put = api.put("/api/jobs/" + job, jobBody("Backend", "Acme", null, "Java", "SQL"));
            long waitedMs = (System.nanoTime() - t0) / 1_000_000;
            assertServiceBusy(put);
            assertThat(waitedMs).as("waited for lock_timeout").isBetween(9_000L, 30_000L);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM job_skill WHERE job_id = ?", Integer.class, job))
                    .as("rolled back").isEqualTo(1);

            // other jobs and other operations are not blocked
            assertThat(api.put("/api/jobs/" + other, jobBody("Other", "Acme", null, "SQL", "Go")).status())
                    .isEqualTo(200);
            assertThat(api.post("/api/jobs", jobBody("New", "Acme", null, "Java")).status()).isEqualTo(201);
            assertThat(api.delete("/api/jobs/" + job).status()).as("DELETE does not take the lock").isEqualTo(204);
        }
    }

    @Test
    void jobUpdateWorksOnceTheLockIsReleased() throws Exception {
        UUID job = job("Backend", "Acme", "Java");
        try (Connection c = rawConnection()) {
            holdJobLock(c, job);
        }
        Res put = api.put("/api/jobs/" + job, jobBody("Backend", "Acme", null, "Java", "SQL"));
        assertThat(put.status()).as(put.toString()).isEqualTo(200);
    }

    // ------------------------------------------------------------------ §12.3: candidate edits

    @Test
    void heldCandidateLockMakesCandidateUpdateServiceBusy() throws Exception {
        UUID other = candidate("Bob", "bob@example.com", "Java");
        try (Connection c = rawConnection()) {
            holdCandidateLock(c, cand);
            Res put = api.put("/api/candidates/" + cand, candidateBody("Ada King", "ada@example.com", null, "Java"));
            assertServiceBusy(put);
            assertThat(jdbc.queryForObject("SELECT full_name FROM candidate WHERE id = ?", String.class, cand))
                    .isEqualTo("Ada Lovelace");
            // another candidate, a create and a delete are not blocked
            assertThat(api.put("/api/candidates/" + other, candidateBody("Bob B", "bob@example.com", null, "SQL"))
                    .status()).isEqualTo(200);
            assertThat(api.post("/api/candidates", candidateBody("Cy", "cy@example.com", null, "Java")).status())
                    .isEqualTo(201);
            assertThat(api.get("/api/candidates/" + cand).status()).isEqualTo(200);
        }
        assertThat(api.put("/api/candidates/" + cand, candidateBody("Ada King", "ada@example.com", null, "Java"))
                .status()).isEqualTo(200);
    }

    @Test
    void heldCandidateLockMakesTheOwnersProfileSaveServiceBusy() throws Exception {
        Res first = api.put("/api/profile", profileBody("Backend Engineer").toString());
        assertThat(first.status()).as(first.toString()).isEqualTo(200);
        UUID owner = UUID.fromString(first.json().get("candidateId").asText());
        try (Connection c = rawConnection()) {
            holdCandidateLock(c, owner);
            Res busy = api.put("/api/profile", profileBody("Staff Engineer").toString());
            assertServiceBusy(busy);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM owner_profile_version", Integer.class))
                    .as("no version appended").isEqualTo(1);
            // the owner's matches still read fine when nothing is stale
        }
        Res again = api.put("/api/profile", profileBody("Staff Engineer").toString());
        assertThat(again.status()).as(again.toString()).isEqualTo(200);
        assertThat(again.json().get("version").asInt()).isEqualTo(2);
    }

    // ------------------------------------------------------------------ §9.2 item 7: the matches endpoint

    @Test
    void heldJobLockGivesMatchesBusyForAStaleTargetOnly() throws Exception {
        UUID fresh = job("Fresh", "Acme", "Java");
        assertThat(candidateMatches(cand).status()).isEqualTo(200);   // fresh row now
        UUID stale = job("Stale", "Acme", "SQL");
        try (Connection c = rawConnection()) {
            holdJobLock(c, fresh);
            holdJobLock(c, stale);
            // fresh job only: served without locks
            Res manual = api.get("/api/candidates/" + cand + "/matches?minScore=0&location=nowhere");
            assertThat(manual.status()).as(manual.toString()).isEqualTo(200);

            long t0 = System.nanoTime();
            Res busy = candidateMatches(cand);
            long waitedMs = (System.nanoTime() - t0) / 1_000_000;
            assertMatchesBusy(busy);
            assertThat(waitedMs).isBetween(9_000L, 30_000L);
            assertThat(rows(stale)).isZero();
        }
        JsonNode after = candidateMatches(cand).json();
        assertThat(after.get("recomputedJobs").asInt()).isEqualTo(1);
        assertThat(after.get("totalElements").asLong()).isEqualTo(2);
    }

    @Test
    void heldCandidateLockGivesMatchesBusyWhenSomethingIsStaleButFreshReadsWork() throws Exception {
        job("A", "Acme", "Java");
        assertThat(candidateMatches(cand).json().get("recomputedJobs").asInt()).isEqualTo(1);
        try (Connection c = rawConnection()) {
            holdCandidateLock(c, cand);
            Res fresh = candidateMatches(cand);
            assertThat(fresh.status()).as("nothing stale: no lock needed").isEqualTo(200);
            assertThat(fresh.json().get("recomputedJobs").asInt()).isZero();

            UUID stale = job("B", "Acme", "SQL");
            assertMatchesBusy(candidateMatches(cand));
            assertThat(rows(stale)).isZero();
            // another candidate is not blocked
            UUID other = candidate("Bob", "bob@example.com", "Java");
            Res bob = candidateMatches(other);
            assertThat(bob.status()).isEqualTo(200);
            assertThat(bob.json().get("recomputedJobs").asInt()).isEqualTo(2);
            // the job side does not take the candidate lock
            assertThat(api.get("/api/jobs/" + stale + "/matches").status()).isEqualTo(200);
        }
    }

    @Test
    void heldJobLockOnAnInScopeStaleJobBlocksAnyFilterIncludingIt() throws Exception {
        UUID stale = job("Stale", "Acme", "Java");
        try (Connection c = rawConnection()) {
            holdJobLock(c, stale);
            // out of scope: origin=feed has no targets → 200 without locks
            Res feedOnly = api.get("/api/candidates/" + cand + "/matches?origin=feed");
            assertThat(feedOnly.status()).isEqualTo(200);
            assertThat(feedOnly.json().get("reason").asText()).isEqualTo("NO_MATCHABLE_JOBS");
            // minScore does not narrow the targets
            assertMatchesBusy(api.get("/api/candidates/" + cand + "/matches?minScore=1"));
        }
    }

    // ------------------------------------------------------------------ §9.2 item 7: parallel writers

    @Test
    void parallelCandidateGetsJobGetsAndJobEditsNeverDeadlockAndEndConsistent() throws Exception {
        java.util.List<UUID> cands = new java.util.ArrayList<>(java.util.List.of(cand));
        for (int i = 0; i < 3; i++) {
            cands.add(candidate("C" + i, "c" + i + "@example.com", i % 2 == 0 ? "Java" : "SQL:2", "Go"));
        }
        java.util.List<UUID> jobs = new java.util.ArrayList<>();
        for (int i = 0; i < 8; i++) {
            jobs.add(job("Job " + i, "Acme", i % 3 == 0 ? "Java" : "SQL", "~Go"));
        }
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(8);
        try {
            java.util.List<java.util.concurrent.Future<Res>> futures = new java.util.ArrayList<>();
            for (int round = 0; round < 3; round++) {
                for (UUID c : cands) {
                    futures.add(pool.submit(() -> candidateMatches(c)));
                }
                for (int i = 0; i < jobs.size(); i++) {
                    UUID j = jobs.get(i);
                    futures.add(pool.submit(() -> api.get("/api/jobs/" + j + "/matches")));
                    if ((i + round) % 2 == 0) {
                        String[] skills = round % 2 == 0 ? new String[] {"Java", "SQL"} : new String[] {"Go"};
                        int n = i;
                        futures.add(pool.submit(() -> api.put("/api/jobs/" + j, jobBody("Job " + n, "Acme", null,
                                skills))));
                    }
                }
            }
            for (java.util.concurrent.Future<Res> f : futures) {
                Res r = f.get(120, java.util.concurrent.TimeUnit.SECONDS);
                assertThat(r.status()).as(r.toString()).isEqualTo(200);
            }
        } finally {
            pool.shutdownNow();
        }
        // bring everything fresh, snapshot, then recompute from scratch and compare
        for (UUID c : cands) {
            assertThat(candidateMatches(c).status()).isEqualTo(200);
        }
        java.util.Map<String, Double> cached = new java.util.HashMap<>();
        jdbc.query("SELECT job_id, candidate_id, score FROM job_match", rs -> {
            cached.put(rs.getString(1) + "/" + rs.getString(2), rs.getDouble(3));
        });
        assertThat(cached).hasSize(cands.size() * jobs.size());
        jdbc.update("DELETE FROM job_match");
        for (UUID c : cands) {
            assertThat(candidateMatches(c).json().get("recomputedJobs").asInt()).isEqualTo(jobs.size());
        }
        java.util.Map<String, Double> fresh = new java.util.HashMap<>();
        jdbc.query("SELECT job_id, candidate_id, score FROM job_match", rs -> {
            fresh.put(rs.getString(1) + "/" + rs.getString(2), rs.getDouble(3));
        });
        assertThat(cached).as("cached scores equal the engine over the final skills").isEqualTo(fresh);
    }
}
