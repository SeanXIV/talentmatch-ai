package com.talentmatch.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import com.talentmatch.support.AbstractApiIT;
import com.talentmatch.support.Api.Res;
import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** §8.10 Batch recompute: 202 + Location, progress resource, skipped jobs, onlyStale, 409, lock failures. */
class RecomputeIT extends AbstractApiIT {

    private static final Set<String> FINISHED = Set.of("SUCCEEDED", "COMPLETED_WITH_ERRORS", "FAILED");

    UUID backend;
    UUID data;
    UUID empty;
    UUID ada;

    @BeforeEach
    void data() {
        skill("Java");
        skill("SQL");
        ada = candidate("Ada", "ada@example.com", "Java", "SQL");
        candidate("Bob", "bob@example.com", "Java");
        candidate("Cy", "cy@example.com");
        candidate("Dee", "dee@example.com", "SQL");
        backend = job("Backend Engineer", "Acme", "Java", "~SQL");
        data = job("Data Engineer", "Acme", "SQL");
        empty = jobWithoutSkills("Loaded From ETL", "Acme");
    }

    @AfterEach
    void noRunLeftActive() {
        // A later test (or class) must not see a run from this one.
        await().atMost(Duration.ofSeconds(30)).until(() -> {
            Res r = api.post("/api/matches/recompute?onlyStale=true", null);
            if (r.status() == 202) {
                awaitFinished(r.json().get("runId").asText());
                return true;
            }
            return false;
        });
    }

    private JsonNode awaitFinished(String runId) {
        AtomicReference<JsonNode> last = new AtomicReference<>();
        await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(100)).until(() -> {
            Res r = api.get("/api/matches/recompute/" + runId);
            assertThat(r.status()).as(r.toString()).isEqualTo(200);
            last.set(r.json());
            return FINISHED.contains(r.json().get("state").asText());
        });
        return last.get();
    }

    private JsonNode start(String query) {
        Res r = api.post("/api/matches/recompute" + (query == null ? "" : "?" + query), null);
        assertThat(r.status()).as(r.toString()).isEqualTo(202);
        String runId = r.json().get("runId").asText();
        assertThat(r.header("Location")).isEqualTo("/api/matches/recompute/" + runId);
        assertThat(r.json().get("state").asText()).isIn("QUEUED", "RUNNING", "SUCCEEDED");
        return r.json();
    }

    @Test
    void fullRecomputeScoresEveryMatchableJobAndSkipsEmptyOnes() {
        JsonNode started = start(null);
        assertThat(started.get("onlyStale").asBoolean()).isFalse();
        assertThat(Instant.parse(started.get("startedAt").asText())).isNotNull();
        JsonNode run = awaitFinished(started.get("runId").asText());

        assertThat(run.get("state").asText()).isEqualTo("SUCCEEDED");
        assertThat(run.get("totalJobs").asInt()).isEqualTo(3);
        assertThat(run.get("processed").asInt()).isEqualTo(2);
        assertThat(run.get("skipped").asInt()).isEqualTo(1);
        assertThat(run.get("failed").asInt()).isZero();
        assertThat(run.get("processed").asInt() + run.get("skipped").asInt()).isEqualTo(run.get("totalJobs").asInt());
        assertThat(run.get("skippedJobIds")).hasSize(1);
        assertThat(run.at("/skippedJobIds/0").asText()).isEqualTo(empty.toString());
        assertThat(run.get("failures")).isEmpty();
        assertThat(run.get("percentComplete").asInt()).isEqualTo(100);
        assertThat(run.get("matchesWritten").asLong()).isEqualTo(8);
        assertThat(run.get("finishedAt").isNull()).isFalse();
        assertThat(run.get("message").asText())
                .isEqualTo("Recompute finished: 2 job(s) recomputed, 1 skipped, 8 match(es) written.");

        assertThat(countMatchRows(backend)).isEqualTo(4);
        assertThat(countMatchRows(data)).isEqualTo(4);
        assertThat(countMatchRows(empty)).isZero();
        // rows are fresh: the dashboard GET recomputes nothing
        assertThat(matches(backend).get("recomputedCandidates").asInt()).isZero();
        JsonNode dataPage = matches(data);
        assertThat(dataPage.get("recomputedCandidates").asInt()).isZero();
        assertThat(candidateNames(dataPage)).containsExactly("Ada", "Dee", "Bob", "Cy");
    }

    @Test
    void onlyStaleWritesNothingWhenFreshAndOnlyStaleRowsAfterAnEdit() {
        awaitFinished(start(null).get("runId").asText());

        JsonNode fresh = awaitFinished(start("onlyStale=true").get("runId").asText());
        assertThat(fresh.get("state").asText()).isEqualTo("SUCCEEDED");
        assertThat(fresh.get("onlyStale").asBoolean()).isTrue();
        assertThat(fresh.get("matchesWritten").asLong()).isZero();
        assertThat(fresh.get("processed").asInt()).isEqualTo(2);
        assertThat(fresh.get("skipped").asInt()).isEqualTo(1);

        api.put("/api/candidates/" + ada, candidateBody("Ada", "ada@example.com", null, "Java"));
        JsonNode afterEdit = awaitFinished(start("onlyStale=true").get("runId").asText());
        assertThat(afterEdit.get("matchesWritten").asLong()).isEqualTo(2);

        JsonNode all = awaitFinished(start("onlyStale=false").get("runId").asText());
        assertThat(all.get("matchesWritten").asLong()).isEqualTo(8);
    }

    @Test
    void secondRunWhileOneIsActiveIs409WithLocationOfTheActiveRun() throws Exception {
        JsonNode first;
        try (Connection c = rawConnection()) {
            holdMatchLock(c, backend);   // the run blocks on this job (up to lock_timeout 10s)
            first = start(null);
            String runId = first.get("runId").asText();
            await().atMost(Duration.ofSeconds(5)).until(() ->
                    "RUNNING".equals(api.get("/api/matches/recompute/" + runId).json().get("state").asText()));

            Res second = api.post("/api/matches/recompute", null);
            JsonNode err = assertError(second, 409, "RECOMPUTE_ALREADY_RUNNING");
            assertThat(second.header("Location")).isEqualTo("/api/matches/recompute/" + runId);
            assertThat(err.get("message").asText())
                    .startsWith("A recompute is already running (started ")
                    .endsWith("). Track it at /api/matches/recompute/" + runId + ".");
            JsonNode progress = api.get("/api/matches/recompute/" + runId).json();
            assertThat(progress.get("message").asText()).startsWith("Recomputing matches: ");
            assertThat(progress.get("finishedAt").isNull()).isTrue();
        } // closing the connection releases the lock
        JsonNode done = awaitFinished(first.get("runId").asText());
        assertThat(done.get("state").asText()).isEqualTo("SUCCEEDED");
        assertThat(done.get("matchesWritten").asLong()).isEqualTo(8);
    }

    @Test
    void jobLockedTooLongIsReportedAsFailureNotCrash() throws Exception {
        JsonNode run;
        try (Connection c = rawConnection()) {
            holdMatchLock(c, data);
            String runId = start(null).get("runId").asText();
            // lock_timeout is 10s: the job fails with 55P03 and the run carries on
            run = awaitFinished(runId);
        }
        assertThat(run.get("state").asText()).isEqualTo("COMPLETED_WITH_ERRORS");
        assertThat(run.get("failed").asInt()).isEqualTo(1);
        assertThat(run.get("processed").asInt()).isEqualTo(1);
        assertThat(run.get("skipped").asInt()).isEqualTo(1);
        assertThat(run.at("/failures/0/jobId").asText()).isEqualTo(data.toString());
        assertThat(run.at("/failures/0/message").asText())
                .isEqualTo("Matches for this job were locked by another request for too long. Run the recompute again.");
        assertThat(run.get("message").asText()).startsWith("Recompute finished with errors: 1 of 3 job(s) failed");
        assertThat(countMatchRows(data)).isZero();
        assertThat(countMatchRows(backend)).isEqualTo(4);
    }

    @Test
    void runsAreListedIndependently() {
        String a = start(null).get("runId").asText();
        awaitFinished(a);
        String b = start("onlyStale=true").get("runId").asText();
        awaitFinished(b);
        assertThat(a).isNotEqualTo(b);
        for (String id : List.of(a, b)) {
            assertThat(api.get("/api/matches/recompute/" + id).json().get("runId").asText()).isEqualTo(id);
        }
    }
}
