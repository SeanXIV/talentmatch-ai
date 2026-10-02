package com.talentmatch.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.talentmatch.support.AbstractApiIT;
import com.talentmatch.support.Api.Res;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** §8.11 Concurrent GETs on a stale job; MATCHES_BUSY when the per-job lock is held. */
class ConcurrencyIT extends AbstractApiIT {

    static final int CANDIDATES = 40;
    UUID job;

    @BeforeEach
    void data() {
        skill("Java");
        skill("SQL");
        skill("Docker");
        for (int i = 0; i < CANDIDATES; i++) {
            String[] skills = switch (i % 4) {
                case 0 -> new String[] {"Java", "SQL", "Docker"};
                case 1 -> new String[] {"Java"};
                case 2 -> new String[] {"Docker"};
                default -> new String[0];
            };
            candidate(String.format("Candidate %02d", i), "c" + i + "@example.com", skills);
        }
        job = job("Backend Engineer", "Acme", "Java", "SQL", "~Docker");
    }

    private List<Res> parallelGets(int n, String query) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(n);
        try {
            CountDownLatch go = new CountDownLatch(1);
            List<Future<Res>> futures = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                futures.add(pool.submit(() -> {
                    go.await();
                    return api.get("/api/jobs/" + job + "/matches?limit=100" + (query == null ? "" : "&" + query));
                }));
            }
            go.countDown();
            List<Res> results = new ArrayList<>();
            for (Future<Res> f : futures) {
                results.add(f.get());
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void parallelGetsOnAStaleJobAgree() throws Exception {
        for (int round = 0; round < 3; round++) {
            if (round > 0) {
                // make everything stale again
                api.put("/api/jobs/" + job, jobBody("Backend Engineer", "Acme", "round " + round, "Java", "SQL", "~Docker"));
            }
            List<Res> results = parallelGets(4, null);
            int total = 0;
            List<UUID> order = null;
            for (Res r : results) {
                assertThat(r.status()).as(r.toString()).isEqualTo(200);
                JsonNode page = r.json();
                total += page.get("recomputedCandidates").asInt();
                assertThat(page.get("totalElements").asLong()).isEqualTo(CANDIDATES);
                List<UUID> ids = candidateIds(page);
                if (order == null) {
                    order = ids;
                } else {
                    assertThat(ids).containsExactlyElementsOf(order);
                }
            }
            assertThat(total).as("round %d", round).isEqualTo(CANDIDATES);
            assertThat(countMatchRows(job)).isEqualTo(CANDIDATES);
            assertThat(jdbc.queryForObject(
                    "SELECT count(DISTINCT candidate_id) FROM job_match WHERE job_id = ?", Integer.class, job))
                    .isEqualTo(CANDIDATES);
        }
    }

    @Test
    void parallelRegenerateRequestsAllSucceed() throws Exception {
        for (Res r : parallelGets(3, "regenerate=true")) {
            assertThat(r.status()).as(r.toString()).isEqualTo(200);
            assertThat(r.json().get("recomputedCandidates").asInt()).isEqualTo(CANDIDATES);
        }
        assertThat(countMatchRows(job)).isEqualTo(CANDIDATES);
    }

    @Test
    void heldLockGivesMatchesBusyForStaleJobsButFreshReadsStillWork() throws Exception {
        matches(job); // fresh now
        UUID other = job("Data Engineer", "Acme", "SQL"); // stale (no rows yet)
        try (Connection c = rawConnection()) {
            holdMatchLock(c, job);
            holdMatchLock(c, other);

            // Fresh job: no lock needed, so it is served from the cache.
            JsonNode fresh = matches(job);
            assertThat(fresh.get("recomputedCandidates").asInt()).isZero();

            long t0 = System.nanoTime();
            Res busy = api.get("/api/jobs/" + other + "/matches");
            long waitedMs = (System.nanoTime() - t0) / 1_000_000;
            JsonNode err = assertError(busy, 503, "MATCHES_BUSY");
            assertThat(busy.header("Retry-After")).isEqualTo("2");
            assertThat(err.get("message").asText()).isEqualTo(
                    "Matches for this job are being recalculated by another request. Please try again in a few seconds.");
            assertThat(err.get("error").asText()).isEqualTo("Service Unavailable");
            assertThat(waitedMs).as("waited for lock_timeout").isBetween(9_000L, 30_000L);
            assertThat(countMatchRows(other)).isZero();

            // regenerate on a fresh job needs the lock too
            assertError(api.get("/api/jobs/" + job + "/matches?regenerate=true"), 503, "MATCHES_BUSY");
        }
        // lock released: works again
        assertThat(matches(other).get("recomputedCandidates").asInt()).isEqualTo(CANDIDATES);
    }
}
