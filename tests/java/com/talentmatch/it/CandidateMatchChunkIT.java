package com.talentmatch.it;

import static com.talentmatch.support.SeekerFixtures.holdJobLock;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.talentmatch.repository.UuidOrder;
import com.talentmatch.support.AbstractApiIT;
import com.talentmatch.support.Api.Res;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/**
 * Phase 6 §4.2 / §6: candidate-side scoring commits chunk by chunk ({@code rescore-chunk=1} here), so
 * after a lock timeout in a later chunk the earlier chunks stay committed and a retry continues with
 * fewer targets.
 */
@TestPropertySource(properties = "talentmatch.matches.rescore-chunk=1")
class CandidateMatchChunkIT extends AbstractApiIT {

    private int rows(UUID cand, UUID job) {
        return jdbc.queryForObject("SELECT count(*) FROM job_match WHERE job_id = ? AND candidate_id = ?",
                Integer.class, job, cand);
    }

    @Test
    void chunksCommittedBeforeALockTimeoutStay() throws Exception {
        skills("Java", "SQL");
        UUID cand = candidate("Ada Lovelace", "ada@example.com", "Java");
        List<UUID> jobs = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            jobs.add(job("Job " + i, "Acme", i % 2 == 0 ? "Java" : "SQL"));
        }
        jobs.sort(UuidOrder.COMPARATOR);
        UUID blocked = jobs.get(2);                    // chunks 0 and 1 commit, chunk 2 times out

        try (Connection c = rawConnection()) {
            holdJobLock(c, blocked);
            Res busy = api.get("/api/candidates/" + cand + "/matches");
            assertError(busy, 503, "MATCHES_BUSY");
            assertThat(busy.header("Retry-After")).isEqualTo("2");
        }
        assertThat(rows(cand, jobs.get(0))).isEqualTo(1);
        assertThat(rows(cand, jobs.get(1))).isEqualTo(1);
        assertThat(rows(cand, jobs.get(2))).isZero();
        assertThat(rows(cand, jobs.get(3))).as("never reached").isZero();

        JsonNode retry = api.get("/api/candidates/" + cand + "/matches").json();
        assertThat(retry.get("recomputedJobs").asInt()).as("only the rest").isEqualTo(2);
        assertThat(retry.get("totalElements").asLong()).isEqualTo(4);
    }

    @Test
    void manySmallChunksScoreEverything() {
        skills("Java");
        UUID cand = candidate("Ada Lovelace", "ada@example.com", "Java");
        for (int i = 0; i < 12; i++) {
            job("Job " + i, "Acme", "Java");
        }
        JsonNode page = api.get("/api/candidates/" + cand + "/matches?limit=5").json();
        assertThat(page.get("recomputedJobs").asInt()).isEqualTo(12);
        assertThat(page.get("totalElements").asLong()).isEqualTo(12);
        assertThat(page.get("matches")).hasSize(5);
    }
}
