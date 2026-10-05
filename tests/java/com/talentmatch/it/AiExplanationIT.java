package com.talentmatch.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import com.talentmatch.support.AbstractAiApiIT;
import com.talentmatch.support.Api.Res;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/** Spec §10 integration 3-6, 11, 12, 14-18: AI explanations end to end with the fake ChatModel. */
@ExtendWith(OutputCaptureExtension.class)
class AiExplanationIT extends AbstractAiApiIT {

    private static final String LABEL = "ollama/qwen2.5:7b-instruct";

    // ------------------------------------------------------------------ 3. success

    @Test
    void firstGetGeneratesTopThreeAndPersistsThem() {
        UUID job = backendJob();
        List<UUID> c = fiveCandidates();

        JsonNode page = matches(job);

        assertThat(fake.calls()).isEqualTo(3);
        assertThat(fake.candidatesAsked()).containsExactlyInAnyOrder("Ada Lovelace", "Bob Builder", "Cy Young");
        assertThat(page.get("explanationsGenerated").asInt()).isEqualTo(3);
        assertThat(statuses(page)).containsExactly("READY", "READY", "READY", "UNAVAILABLE", "UNAVAILABLE");
        assertInvariant(page);
        for (int i = 0; i < 3; i++) {
            JsonNode m = item(page, i);
            JsonNode ex = m.get("explanation");
            assertThat(ex.get("source").asText()).isEqualTo("AI");
            assertThat(ex.get("model").asText()).isEqualTo(LABEL);
            assertThat(Instant.parse(ex.get("generatedAt").asText())).isNotNull();
            assertThat(ex.get("headline").asText()).startsWith("AI headline ");
            assertThat(m.get("aiExplanation").asText()).startsWith("AI text #").isEqualTo(ex.get("text").asText());
            assertThat(ex.get("strengths").get(0).asText()).isEqualTo("Java experience");

            Map<String, Object> row = explanationRow(job, c.get(i));
            assertThat(row.get("ai_explanation")).isEqualTo(m.get("aiExplanation").asText());
            assertThat((String) row.get("hash")).matches("^[0-9a-f]{64}$");
            assertThat(row.get("model")).isEqualTo(LABEL);
            assertThat((String) row.get("payload")).contains("\"headline\"").contains("\"strengths\"");
            assertThat(((Timestamp) row.get("generated_at")).toInstant())
                    .isEqualTo(Instant.parse(ex.get("generatedAt").asText()));
        }
        for (int i = 3; i < 5; i++) {
            JsonNode ex = item(page, i).get("explanation");
            assertThat(ex.get("source").asText()).isEqualTo("TEMPLATE");
            assertThat(ex.get("reason").asText()).isEqualTo("NOT_IN_TOP_N");
            assertThat(ex.get("note").asText()).isEqualTo("AI explanations are generated for the top 3 matches only, "
                    + "so this summary was built from the skill breakdown.");
            assertThat(explanationRow(job, c.get(i)).get("ai_explanation")).isNull();
        }
        assertThat(item(page, 3).at("/explanation/headline").asText()).isEqualTo("Partial match: 1 of 2 required skills");
        assertThat(item(page, 3).at("/explanation/text").asText()).isEqualTo("Di Prince has 1 of 2 required skills: "
                + "Java; missing: SQL. Nice-to-have skills: has Docker; missing Kubernetes.");
    }

    // ------------------------------------------------------------------ 4. caching

    @Test
    void secondGetAndRecomputeReuseStoredExplanations() {
        UUID job = backendJob();
        List<UUID> c = fiveCandidates();
        JsonNode first = matches(job);
        Timestamp computedBefore = jdbc.queryForObject(
                "SELECT computed_at FROM job_match WHERE job_id = ? AND candidate_id = ?", Timestamp.class, job, c.get(0));

        JsonNode second = matches(job);
        assertThat(fake.calls()).isEqualTo(3);
        assertThat(second.get("explanationsGenerated").asInt()).isZero();
        for (int i = 0; i < 3; i++) {
            assertThat(item(second, i).get("explanationStatus").asText()).isEqualTo("READY");
            assertThat(item(second, i).get("aiExplanation").asText())
                    .isEqualTo(item(first, i).get("aiExplanation").asText());
            assertThat(item(second, i).at("/explanation/generatedAt").asText())
                    .isEqualTo(item(first, i).at("/explanation/generatedAt").asText());
        }

        Res started = api.post("/api/matches/recompute", null);
        assertThat(started.status()).as(started.toString()).isEqualTo(202);
        String runId = started.json().get("runId").asText();
        await().atMost(Duration.ofSeconds(30)).until(() -> !List.of("QUEUED", "RUNNING")
                .contains(api.get("/api/matches/recompute/" + runId).json().get("state").asText()));
        Timestamp computedAfter = jdbc.queryForObject(
                "SELECT computed_at FROM job_match WHERE job_id = ? AND candidate_id = ?", Timestamp.class, job, c.get(0));
        assertThat(computedAfter.toInstant()).isAfter(computedBefore.toInstant());
        assertThat(storedExplanations(job)).as("recompute never touches explanations").isEqualTo(3);

        JsonNode third = matches(job);
        assertThat(fake.calls()).isEqualTo(3);
        assertThat(statuses(third).subList(0, 3)).containsOnly("READY");
        assertThat(third.get("explanationsGenerated").asInt()).isZero();
    }

    // ------------------------------------------------------------------ 5. staleness

    @Test
    void summaryChangeRegeneratesOneEmailChangeNoneJobSkillChangeTopThree() {
        UUID job = backendJob();
        List<UUID> c = fiveCandidates();
        JsonNode first = matches(job);
        assertThat(fake.calls()).isEqualTo(3);

        Res put = api.put("/api/candidates/" + c.get(0), candidateBody("Ada Lovelace", "ada@example.com",
                "Ada summary: rewritten.", "Java:5", "SQL", "Docker", "Kubernetes"));
        assertThat(put.status()).as(put.toString()).isEqualTo(200);
        JsonNode afterSummary = matches(job);
        assertThat(fake.calls()).isEqualTo(4);
        assertThat(fake.candidatesAsked().get(3)).isEqualTo("Ada Lovelace");
        assertThat(afterSummary.get("explanationsGenerated").asInt()).isEqualTo(1);
        assertThat(item(afterSummary, 0).get("aiExplanation").asText())
                .isNotEqualTo(item(first, 0).get("aiExplanation").asText());
        assertThat(statuses(afterSummary).subList(0, 3)).containsOnly("READY");

        put = api.put("/api/candidates/" + c.get(1), candidateBody("Bob Builder", "bob.new@example.com",
                "Bob summary: builds things.", "Java:3", "SQL", "Docker"));
        assertThat(put.status()).as(put.toString()).isEqualTo(200);
        JsonNode afterEmail = matches(job);
        assertThat(afterEmail.get("recomputedCandidates").asInt()).as("score refreshed").isEqualTo(1);
        assertThat(fake.calls()).as("email is not in the prompt").isEqualTo(4);
        assertThat(item(afterEmail, 1).get("explanationStatus").asText()).isEqualTo("READY");

        put = api.put("/api/jobs/" + job, jobBody("Backend Engineer", "Acme", "Build secret-sauce APIs for payments.",
                "Java", "SQL", "~Docker", "~Kubernetes", "~Git"));
        assertThat(put.status()).as(put.toString()).isEqualTo(200);
        JsonNode afterJob = matches(job);
        assertThat(fake.calls()).isEqualTo(7);
        assertThat(afterJob.get("explanationsGenerated").asInt()).isEqualTo(3);
        assertThat(statuses(afterJob)).containsExactly("READY", "READY", "READY", "UNAVAILABLE", "UNAVAILABLE");
        assertInvariant(afterJob);
    }

    @Test
    void failedRefreshAfterAnEditIsStaleAndPushedOutOfTopNIsStaleNotInTopN() {
        UUID job = backendJob();
        List<UUID> c = fiveCandidates();
        matches(job);
        String oldText = (String) explanationRow(job, c.get(0)).get("ai_explanation");

        fake.fail(new IllegalStateException("model exploded"));
        api.put("/api/candidates/" + c.get(0), candidateBody("Ada Lovelace", "ada@example.com",
                "Ada summary: changed.", "Java:5", "SQL", "Docker", "Kubernetes"));
        JsonNode stale = matches(job);
        JsonNode ada = item(stale, 0);
        assertThat(ada.get("explanationStatus").asText()).isEqualTo("STALE");
        assertThat(ada.get("aiExplanation").isNull()).isTrue();
        assertThat(ada.at("/explanation/source").asText()).isEqualTo("TEMPLATE");
        assertThat(ada.at("/explanation/reason").asText()).isEqualTo("PROVIDER_UNAVAILABLE");
        assertThat(ada.at("/explanation/note").asText())
                .startsWith("The previous AI explanation is out of date because the candidate or job changed. ");
        assertThat(explanationRow(job, c.get(0)).get("ai_explanation")).as("old text kept in DB").isEqualTo(oldText);
        assertThat(statuses(stale).subList(1, 3)).containsOnly("READY");
        assertInvariant(stale);

        // Bob drops to Java only (33%) and falls behind Di (50%): out of the top 3 with outdated text
        fake.reset();
        circuit.recordSuccess();
        api.put("/api/candidates/" + c.get(1), candidateBody("Bob Builder", "bob@example.com",
                "Bob summary: builds things.", "Java:3"));
        JsonNode moved = matches(job);
        JsonNode bob = null;
        for (JsonNode m : moved.get("matches")) {
            if (m.get("candidateName").asText().equals("Bob Builder")) {
                bob = m;
            }
        }
        assertThat(bob.get("rank").asInt()).isGreaterThan(3);
        assertThat(bob.get("explanationStatus").asText()).isEqualTo("STALE");
        assertThat(bob.at("/explanation/reason").asText()).isEqualTo("NOT_IN_TOP_N");
        assertThat(bob.at("/explanation/note").asText()).startsWith("The previous AI explanation is out of date")
                .endsWith("AI explanations are generated for the top 3 matches only, so this summary was built "
                        + "from the skill breakdown.");
        assertThat(fake.candidatesAsked()).contains("Di Prince").doesNotContain("Bob Builder");
        assertInvariant(moved);
    }

    // ------------------------------------------------------------------ 6. budget / pending

    @Test
    void slowModelGivesPendingWithinBudgetThenPersistsInTheBackground() {
        UUID job = backendJob();
        List<UUID> c = fiveCandidates();
        fake.delayDefault(Duration.ofMillis(2_500));

        long t0 = System.nanoTime();
        JsonNode page = matches(job);
        long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
        assertThat(ms).as("response time <= budget + 1s").isLessThanOrEqualTo(3_000);
        assertThat(statuses(page).subList(0, 3)).containsOnly("PENDING");
        assertThat(reasons(page).subList(0, 3)).containsOnly("GENERATING");
        assertThat(item(page, 0).at("/explanation/note").asText()).isEqualTo("An AI explanation is being written. "
                + "Reload in a few seconds; until then this summary was built from the skill breakdown.");
        assertThat(page.get("explanationsGenerated").asInt()).isZero();
        assertInvariant(page);

        await().atMost(Duration.ofSeconds(15)).until(() -> storedExplanations(job) == 3);
        int calls = fake.calls();
        assertThat(calls).isEqualTo(3);
        JsonNode next = matches(job);
        assertThat(statuses(next).subList(0, 3)).containsOnly("READY");
        assertThat(fake.calls()).isEqualTo(calls);
        assertThat(next.get("explanationsGenerated").asInt()).isZero();
        assertThat(explanationRow(job, c.get(0)).get("ai_explanation"))
                .isEqualTo(item(next, 0).get("aiExplanation").asText());
    }

    // ------------------------------------------------------------------ 11. top N

    @Test
    void onlyTheGlobalTopNIsEverGenerated() {
        UUID job = backendJob();
        fiveCandidates();
        JsonNode second = matches(job, "page=1&limit=3");
        assertThat(fake.calls()).as("ranks 4-5 are never eligible").isZero();
        assertThat(second.get("explanationsGenerated").asInt()).isZero();
        assertThat(reasons(second)).containsOnly("NOT_IN_TOP_N");
        assertThat(item(second, 0).get("rank").asInt()).isEqualTo(4);

        JsonNode all = matches(job, "limit=10");
        assertThat(fake.calls()).isEqualTo(3);
        assertThat(all.get("explanationsGenerated").asInt()).isEqualTo(3);
    }

    // ------------------------------------------------------------------ 12. regenerate

    @Test
    void regenerateForcesNewCallsAndAFailedRegenerationKeepsTheOldText() {
        UUID job = backendJob();
        List<UUID> c = fiveCandidates();
        JsonNode first = matches(job);

        JsonNode regen = matches(job, "regenerate=true");
        assertThat(fake.calls()).isEqualTo(6);
        assertThat(regen.get("explanationsGenerated").asInt()).isEqualTo(3);
        assertThat(regen.get("recomputedCandidates").asInt()).as("regenerate rescores all").isEqualTo(5);
        for (int i = 0; i < 3; i++) {
            assertThat(item(regen, i).get("aiExplanation").asText())
                    .isNotEqualTo(item(first, i).get("aiExplanation").asText());
            assertThat(explanationRow(job, c.get(i)).get("ai_explanation"))
                    .isEqualTo(item(regen, i).get("aiExplanation").asText());
        }

        clock.advance(Duration.ofSeconds(61)); // past the regenerate window
        fake.fail(new IllegalStateException("provider down"));
        JsonNode failed = matches(job, "regenerate=true");
        assertThat(fake.calls()).isEqualTo(9);
        assertThat(failed.get("explanationsGenerated").asInt()).isZero();
        for (int i = 0; i < 3; i++) {
            assertThat(item(failed, i).get("explanationStatus").asText()).isEqualTo("READY");
            assertThat(item(failed, i).get("aiExplanation").asText())
                    .isEqualTo(item(regen, i).get("aiExplanation").asText());
            assertThat(explanationRow(job, c.get(i)).get("ai_explanation"))
                    .isEqualTo(item(regen, i).get("aiExplanation").asText());
        }
        assertInvariant(failed);
    }

    // ------------------------------------------------------------------ 14. single-flight

    @Test
    void parallelGetsShareOneCallPerCandidate() throws Exception {
        UUID job = backendJob();
        fiveCandidates();
        CountDownLatch latch = fake.block();
        try {
            CompletableFuture<Res> a = CompletableFuture.supplyAsync(() -> getMatches(job, null));
            CompletableFuture<Res> b = CompletableFuture.supplyAsync(() -> getMatches(job, null));
            Res ra = a.get(20, TimeUnit.SECONDS);
            Res rb = b.get(20, TimeUnit.SECONDS);
            assertThat(ra.status()).isEqualTo(200);
            assertThat(rb.status()).isEqualTo(200);
            for (Res r : List.of(ra, rb)) {
                assertThat(statuses(r.json()).subList(0, 3)).allMatch(s -> s.equals("READY") || s.equals("PENDING"));
                assertInvariant(r.json());
            }
        } finally {
            latch.countDown();
        }
        awaitAiIdle();
        assertThat(fake.calls()).isEqualTo(3);
        assertThat(storedExplanations(job)).isEqualTo(3);
    }

    // ------------------------------------------------------------------ 15. transaction boundary

    @Test
    void advisoryLockAndConnectionAreReleasedBeforeTheModelCall() throws Exception {
        UUID job = backendJob();
        fiveCandidates();
        CountDownLatch latch = fake.block();
        try {
            CompletableFuture<Res> pending = CompletableFuture.supplyAsync(() -> getMatches(job, null));
            await().atMost(Duration.ofSeconds(10)).until(() -> fake.calls() >= 1);
            try (Connection raw = rawConnection()) {
                try (PreparedStatement ps = raw.prepareStatement(
                        "SELECT pg_try_advisory_lock(hashtextextended(?, 0))")) {
                    ps.setString(1, "job_match:" + job);
                    try (ResultSet rs = ps.executeQuery()) {
                        rs.next();
                        assertThat(rs.getBoolean(1)).as("job lock is free while the model is working").isTrue();
                    }
                }
                try (PreparedStatement ps = raw.prepareStatement("SELECT count(*) FROM pg_stat_activity "
                        + "WHERE datname = current_database() AND state LIKE 'idle in transaction%' "
                        + "AND pid <> pg_backend_pid()")) {
                    try (ResultSet rs = ps.executeQuery()) {
                        rs.next();
                        assertThat(rs.getInt(1)).as("no app session idle in transaction").isZero();
                    }
                }
            }
            Res r = pending.get(20, TimeUnit.SECONDS);
            assertThat(r.status()).isEqualTo(200);
            assertThat(statuses(r.json()).subList(0, 3)).containsOnly("PENDING");
        } finally {
            latch.countDown();
        }
    }

    // ------------------------------------------------------------------ 16. persist guard

    @Test
    void editDuringGenerationDiscardsTheResultAndTheNextGetRegenerates() throws Exception {
        UUID job = backendJob();
        List<UUID> c = fiveCandidates();
        CountDownLatch latch = fake.block();
        try {
            JsonNode page = matches(job);
            assertThat(statuses(page).subList(0, 3)).containsOnly("PENDING");
            Res put = api.put("/api/candidates/" + c.get(0), candidateBody("Ada Lovelace", "ada@example.com",
                    "Ada summary: analytical engines.", "Java:5", "SQL", "Docker"));
            assertThat(put.status()).as(put.toString()).isEqualTo(200);
        } finally {
            latch.countDown();
        }
        awaitAiIdle();
        assertThat(fake.calls()).isEqualTo(3);
        assertThat(explanationRow(job, c.get(0)).get("ai_explanation")).as("guarded write found a change").isNull();
        assertThat(explanationRow(job, c.get(1)).get("ai_explanation")).as("unchanged pairs persist").isNotNull();

        JsonNode next = matches(job);
        assertThat(fake.calls()).isEqualTo(4);
        assertThat(fake.candidatesAsked().get(3)).isEqualTo("Ada Lovelace");
        assertThat(item(next, 0).get("explanationStatus").asText()).isEqualTo("READY");
        assertThat(explanationRow(job, c.get(0)).get("ai_explanation")).isNotNull();
    }

    // ------------------------------------------------------------------ 17. compatibility

    @Test
    void phaseTwoShapeIsKeptAndExtendedAdditively() {
        UUID job = backendJob();
        fiveCandidates();
        JsonNode page = matches(job);
        for (String f : List.of("jobId", "jobTitle", "company", "matchable", "reason", "message", "page", "limit",
                "totalPages", "totalElements", "recomputedCandidates", "explanationsGenerated", "matches")) {
            assertThat(page.has(f)).as(f).isTrue();
        }
        assertThat(page.get("explanationsGenerated").isInt()).isTrue();
        JsonNode m = item(page, 0);
        assertThat(m.get("rank").isInt()).isTrue();
        assertThat(m.get("score").isNumber()).isTrue();
        assertThat(m.get("scorePercent").isInt()).isTrue();
        assertThat(m.get("summary").isTextual()).isTrue();
        assertThat(m.get("breakdown").isObject()).isTrue();
        assertThat(m.get("aiExplanation").isTextual()).isTrue();
        assertThat(m.get("explanationStatus").isTextual()).isTrue();
        assertThat(m.get("computedAt").isTextual()).isTrue();
        List<String> order = new java.util.ArrayList<>();
        m.fieldNames().forEachRemaining(order::add);
        assertThat(order).containsExactly("rank", "candidateId", "candidateName", "score", "scorePercent", "summary",
                "breakdown", "aiExplanation", "explanationStatus", "explanation", "computedAt");
        List<String> exFields = new java.util.ArrayList<>();
        m.get("explanation").fieldNames().forEachRemaining(exFields::add);
        assertThat(exFields).containsExactly("source", "headline", "text", "strengths", "gaps", "model",
                "generatedAt", "reason", "note");
        page.get("matches").forEach(x -> assertThat(x.get("explanation").isObject()).isTrue());

        JsonNode noSkills = matches(jobWithoutSkills("ETL job", "Acme"));
        assertThat(noSkills.get("matchable").asBoolean()).isFalse();
        assertThat(noSkills.get("explanationsGenerated").asInt()).isZero();
        assertThat(noSkills.get("matches").size()).isZero();

        jdbc.execute("DELETE FROM candidate_skill");
        jdbc.execute("DELETE FROM job_match");
        jdbc.execute("DELETE FROM candidate");
        JsonNode none = matches(job);
        assertThat(none.get("reason").asText()).isEqualTo("NO_CANDIDATES");
        assertThat(none.get("explanationsGenerated").asInt()).isZero();
        assertThat(fake.calls()).isEqualTo(3);
    }

    // ------------------------------------------------------------------ 18. logging

    @Test
    void infoLogsCarryOutcomeButNoNamesSummariesOrDescriptions(CapturedOutput output) {
        UUID job = backendJob();
        fiveCandidates();
        int before = output.getAll().length();
        matches(job);
        String logs = output.getAll().substring(before);
        List<String> lines = logs.lines().filter(l -> l.contains("AI explanation job=" + job)).toList();
        assertThat(lines).hasSize(3);
        assertThat(lines).allSatisfy(l -> assertThat(l).contains(" INFO ").contains("provider=ollama")
                .contains("model=qwen2.5:7b-instruct").contains("outcome=success").containsPattern("latencyMs=\\d+")
                .contains("inputTokens=100").contains("outputTokens=40"));
        for (String secret : List.of("Ada Lovelace", "Bob Builder", "Cy Young", "Ada summary", "Bob summary",
                "secret-sauce", "ada@example.com", "AI text #")) {
            assertThat(logs).as("logs must not contain '%s'", secret).doesNotContain(secret);
        }
    }
}
