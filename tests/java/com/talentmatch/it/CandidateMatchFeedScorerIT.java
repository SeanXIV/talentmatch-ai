package com.talentmatch.it;

import static com.talentmatch.support.SeekerFixtures.feed;
import static com.talentmatch.support.SeekerFixtures.feedJob;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.talentmatch.feed.FeedScorer;
import com.talentmatch.support.AbstractApiIT;
import com.talentmatch.support.Api.Res;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Phase 6 §9.2 item 3 (a feed job the feed pipeline already scored for the owner is reused by the
 * owner's candidate GET) and item 7 (candidate GETs, job GETs and {@link FeedScorer#score} on
 * overlapping jobs in parallel: no deadlock, and the final scores equal a from-scratch recompute).
 * {@link FeedScorer} is the scoring step {@code FeedProcessor} runs for the owner.
 */
class CandidateMatchFeedScorerIT extends AbstractApiIT {

    @Autowired
    private FeedScorer feedScorer;

    @BeforeEach
    void data() {
        skills("Java", "SQL", "Go");
    }

    private UUID owner() {
        ObjectNode p = mapper.createObjectNode();
        p.put("fullName", "Olive Owner");
        p.put("email", "olive@example.com");
        p.put("headline", "Backend Engineer");
        ArrayNode skills = p.putArray("skills");
        skills.addObject().put("name", "Java").put("years", 5);
        skills.addObject().put("name", "SQL");
        ObjectNode body = mapper.createObjectNode();
        body.set("profile", p);
        Res saved = api.put("/api/profile", body.toString());
        assertThat(saved.status()).as(saved.toString()).isEqualTo(200);
        return UUID.fromString(saved.json().get("candidateId").asText());
    }

    private Instant computedAt(UUID jobId, UUID candidateId) {
        return jdbc.queryForObject("SELECT computed_at FROM job_match WHERE job_id = ? AND candidate_id = ?",
                java.sql.Timestamp.class, jobId, candidateId).toInstant();
    }

    private Map<String, Double> scores() {
        Map<String, Double> out = new HashMap<>();
        jdbc.query("SELECT job_id, candidate_id, score FROM job_match", rs -> {
            out.put(rs.getString(1) + "/" + rs.getString(2), rs.getDouble(3));
        });
        return out;
    }

    // ------------------------------------------------------------------ §9.2 item 3

    @Test
    void aFeedJobScoredByTheFeedPipelineIsReusedByTheOwnersGet() {
        UUID owner = owner();
        UUID a = feedJob(jdbc, "Feed Java", "Acme", feed(), "Java", "~Go");
        UUID b = feedJob(jdbc, "Feed SQL", "Acme", feed(), "SQL");
        assertThat(feedScorer.score(a, owner)).isPresent();
        assertThat(feedScorer.score(b, owner)).isPresent();
        Instant ca = computedAt(a, owner);
        Instant cb = computedAt(b, owner);

        Res r = api.get("/api/profile/matches");
        assertThat(r.status()).as(r.toString()).isEqualTo(200);
        JsonNode page = r.json();
        assertThat(page.get("recomputedJobs").asInt()).as("feed scores reused").isZero();
        assertThat(page.get("totalElements").asInt()).isEqualTo(2);
        assertThat(computedAt(a, owner)).isEqualTo(ca);
        assertThat(computedAt(b, owner)).isEqualTo(cb);
        for (JsonNode m : page.get("matches")) {
            UUID id = UUID.fromString(m.get("jobId").asText());
            assertThat(Instant.parse(m.get("computedAt").asText())).isEqualTo(id.equals(a) ? ca : cb);
        }

        // the job side reuses them too, for the owner
        JsonNode jobSide = matches(a);
        assertThat(jobSide.get("recomputedCandidates").asInt()).isZero();
        assertThat(computedAt(a, owner)).isEqualTo(ca);
    }

    // ------------------------------------------------------------------ §9.2 item 7

    @Test
    void parallelCandidateGetsJobGetsAndFeedScoringNeverDeadlockAndEndConsistent() throws Exception {
        UUID owner = owner();
        List<UUID> cands = new ArrayList<>(List.of(owner));
        for (int i = 0; i < 3; i++) {
            cands.add(candidate("C" + i, "c" + i + "@example.com", i % 2 == 0 ? "Java" : "SQL:2", "Go"));
        }
        List<UUID> jobs = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            jobs.add(i % 2 == 0 ? feedJob(jdbc, "Feed " + i, "Acme", feed(), i % 3 == 0 ? "Java" : "SQL", "~Go")
                    : job("Job " + i, "Acme", i % 3 == 0 ? "Java" : "SQL", "~Go"));
        }
        ExecutorService pool = Executors.newFixedThreadPool(8);
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (int round = 0; round < 3; round++) {
                for (UUID c : cands) {
                    futures.add(pool.submit(ok(() -> api.get("/api/candidates/" + c + "/matches"))));
                }
                for (int i = 0; i < jobs.size(); i++) {
                    UUID j = jobs.get(i);
                    futures.add(pool.submit(ok(() -> api.get("/api/jobs/" + j + "/matches"))));
                    // overlapping feed scoring for the owner, in the opposite job order
                    UUID k = jobs.get(jobs.size() - 1 - i);
                    futures.add(pool.submit(() -> feedScorer.score(k, owner)));
                    if (i % 2 == 1 && (i + round) % 3 == 0) {
                        String[] skills = round % 2 == 0 ? new String[] {"Java", "SQL"} : new String[] {"Go"};
                        int n = i;
                        futures.add(pool.submit(ok(() -> api.put("/api/jobs/" + j,
                                jobBody("Job " + n, "Acme", null, skills)))));
                    }
                }
            }
            for (Future<?> f : futures) {
                f.get(120, TimeUnit.SECONDS); // any deadlock or lock-timeout error surfaces here
            }
        } finally {
            pool.shutdownNow();
        }

        for (UUID c : cands) {
            assertThat(api.get("/api/candidates/" + c + "/matches").status()).isEqualTo(200);
        }
        Map<String, Double> cached = scores();
        assertThat(cached).hasSize(cands.size() * jobs.size());
        jdbc.update("DELETE FROM job_match");
        for (UUID c : cands) {
            assertThat(api.get("/api/candidates/" + c + "/matches").json().get("recomputedJobs").asInt())
                    .isEqualTo(jobs.size());
        }
        assertThat(cached).as("cached scores equal the engine over the final skills").isEqualTo(scores());
    }

    private static Callable<Res> ok(Callable<Res> call) {
        return () -> {
            Res r = call.call();
            assertThat(r.status()).as(r.toString()).isEqualTo(200);
            return r;
        };
    }
}
