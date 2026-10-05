package com.talentmatch.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.talentmatch.support.AbstractAiApiIT;
import com.talentmatch.support.Api.Res;
import com.talentmatch.support.FakeChatModel;
import dev.langchain4j.model.output.FinishReason;
import java.net.http.HttpTimeoutException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

/** Spec §10 integration 2, 6 (timeout type), 7-10: failures always degrade to 200 + template. */
class AiFailureIT extends AbstractAiApiIT {

    private UUID job(String title) {
        Res r = api.post("/api/jobs", jobBody(title, "Acme", "Payments APIs.", "Java", "SQL", "~Docker", "~Kubernetes"));
        assertThat(r.status()).as(r.toString()).isEqualTo(201);
        return UUID.fromString(r.json().get("id").asText());
    }

    private void assertTopThree(JsonNode page, String status, String reason) {
        for (int i = 0; i < 3; i++) {
            JsonNode m = item(page, i);
            assertThat(m.get("explanationStatus").asText()).as("item %d", i).isEqualTo(status);
            assertThat(m.at("/explanation/source").asText()).isEqualTo("TEMPLATE");
            assertThat(m.at("/explanation/reason").asText()).as("item %d", i).isEqualTo(reason);
        }
        assertInvariant(page);
    }

    // ------------------------------------------------------------------ 6/7. exceptions

    @Test
    void timeoutTypeAndOtherExceptionsGive200ProviderUnavailableWithoutLeaks() {
        fiveCandidates();
        UUID timeoutJob = job("Timeout job");
        fake.fail(new RuntimeException("wrapped", new HttpTimeoutException("request timed out")));
        Res r = getMatches(timeoutJob, null);
        assertThat(r.status()).isEqualTo(200);
        assertTopThree(r.json(), "UNAVAILABLE", "PROVIDER_UNAVAILABLE");
        assertThat(r.json().at("/matches/0/explanation/note").asText()).isEqualTo("The AI explanation service is "
                + "unavailable right now, so this summary was built from the skill breakdown.");

        circuit.recordSuccess();
        UUID errorJob = job("Error job");
        fake.fail(new IllegalStateException("java.net.ConnectException: at org.example SELECT secret stack"));
        Res e = getMatches(errorJob, null);
        assertThat(e.status()).isEqualTo(200);
        assertTopThree(e.json(), "UNAVAILABLE", "PROVIDER_UNAVAILABLE");
        assertNoLeaks(e);
        assertThat(storedExplanations(errorJob)).isZero();
    }

    // ------------------------------------------------------------------ 8. refusal-like / invalid output

    @Test
    void invalidOrRefusedOutputGivesGenerationFailedAndPersistsNothing() {
        fiveCandidates();
        Map<String, Runnable> cases = new LinkedHashMap<>();
        cases.put("non-JSON", () -> fake.respondText("Sure! Ada is great at Java.", FinishReason.STOP));
        cases.put("empty", () -> fake.respondText("", FinishReason.STOP));
        cases.put("blank headline", () -> fake.respondText("{\"headline\":\"  \",\"explanation\":"
                + "\"A perfectly long explanation about Java skills.\",\"strengths\":[],\"gaps\":[]}", FinishReason.STOP));
        cases.put("CONTENT_FILTER", () -> fake.respondFinish(FinishReason.CONTENT_FILTER));
        cases.put("OTHER", () -> fake.respondFinish(FinishReason.OTHER));
        cases.put("LENGTH", () -> fake.respondFinish(FinishReason.LENGTH));
        cases.put("email echo", () -> fake.respondText("{\"headline\":\"Fit\",\"explanation\":"
                + "\"Contact ada@example.com about the Java role.\",\"strengths\":[],\"gaps\":[]}", FinishReason.STOP));
        cases.put("prompt echo", () -> fake.respondText("{\"headline\":\"Fit\",\"explanation\":"
                + "\"According to MATCH FACTS the candidate has Java.\",\"strengths\":[],\"gaps\":[]}", FinishReason.STOP));
        for (Map.Entry<String, Runnable> c : cases.entrySet()) {
            UUID j = job("Job " + c.getKey());
            int before = fake.calls();
            c.getValue().run();
            Res r = getMatches(j, null);
            assertThat(r.status()).as(c.getKey()).isEqualTo(200);
            assertThat(fake.calls() - before).as(c.getKey()).isEqualTo(3);
            for (int i = 0; i < 3; i++) {
                assertThat(item(r.json(), i).at("/explanation/reason").asText()).as(c.getKey())
                        .isEqualTo("GENERATION_FAILED");
                assertThat(item(r.json(), i).get("explanationStatus").asText()).as(c.getKey()).isEqualTo("UNAVAILABLE");
            }
            assertThat(item(r.json(), 0).at("/explanation/note").asText()).isEqualTo("An AI explanation couldn't be "
                    + "produced for this match, so this summary was built from the skill breakdown.");
            assertInvariant(r.json());
            assertNoLeaks(r);
            assertThat(storedExplanations(j)).as(c.getKey()).isZero();
        }
        assertThat(circuit.consecutiveFailures()).as("bad output never opens the circuit").isZero();
    }

    @Test
    void hallucinatedStrengthIsDroppedAndTheExplanationIsReady() {
        fiveCandidates();
        UUID j = job("Hallucination job");
        fake.respondText("{\"headline\":\"Good fit\",\"explanation\":\"The candidate knows Java well and more.\","
                + "\"strengths\":[\"Rust wizard\",\"- Java\",\"Haskell\"],\"gaps\":[\"Cobol\"]}", FinishReason.STOP);
        JsonNode page = matches(j);
        assertThat(statuses(page).subList(0, 3)).containsOnly("READY");
        JsonNode ex = item(page, 0).get("explanation");
        assertThat(ex.get("strengths")).hasSize(1);
        assertThat(ex.get("strengths").get(0).asText()).isEqualTo("Java");
        assertThat(ex.get("gaps")).isEmpty();
        assertThat(storedExplanations(j)).isEqualTo(3);
    }

    // ------------------------------------------------------------------ 9. backoff

    @Test
    void failedPairsAreNotRetriedUntilRegenerate() {
        fiveCandidates();
        UUID j = job("Backoff job");
        fake.respondText("not json", FinishReason.STOP);
        assertTopThree(matches(j), "UNAVAILABLE", "GENERATION_FAILED");
        assertThat(fake.calls()).isEqualTo(3);

        fake.reset(); // the model would now answer; backoff still holds
        JsonNode again = matches(j);
        assertThat(fake.calls()).isZero();
        assertTopThree(again, "UNAVAILABLE", "GENERATION_FAILED");

        JsonNode forced = matches(j, "regenerate=true");
        assertThat(fake.calls()).isEqualTo(3);
        assertThat(statuses(forced).subList(0, 3)).containsOnly("READY");
    }

    @Test
    void providerFailureBackoffKeepsItsReason() {
        fiveCandidates();
        UUID j = job("Provider backoff job");
        fake.fail(new IllegalStateException("down"));
        matches(j);
        int calls = fake.calls();
        circuit.recordSuccess(); // isolate backoff from the circuit
        assertTopThree(matches(j), "UNAVAILABLE", "PROVIDER_UNAVAILABLE");
        assertThat(fake.calls()).isEqualTo(calls);
    }

    // ------------------------------------------------------------------ 10. circuit

    @Test
    void threeProviderErrorsOpenTheCircuitAndHealthIsDegradedButUp() {
        fiveCandidates();
        fake.fail(new IllegalStateException("connection refused"));
        matches(job("Circuit job A"));
        assertThat(fake.calls()).isEqualTo(3);

        JsonNode skipped = matches(job("Circuit job B"));
        assertThat(fake.calls()).as("circuit open: no model calls").isEqualTo(3);
        assertTopThree(skipped, "UNAVAILABLE", "PROVIDER_UNAVAILABLE");

        Res health = api.get("/actuator/health");
        assertThat(health.status()).as(health.toString()).isEqualTo(200);
        assertThat(health.json().get("status").asText()).isEqualTo("UP");
        JsonNode ai = health.json().at("/components/ai");
        assertThat(ai.get("status").asText()).isEqualTo("DEGRADED");
        assertThat(ai.at("/details/circuit").asText()).isEqualTo("OPEN");
        assertThat(ai.at("/details/provider").asText()).isEqualTo("ollama");
        assertThat(ai.at("/details/model").asText()).isEqualTo("qwen2.5:7b-instruct");
        assertThat(ai.at("/details/consecutiveFailures").asInt()).isGreaterThanOrEqualTo(3);
        assertThat(ai.at("/details/lastFailure").asText()).isEqualTo("PROVIDER_ERROR");
        assertThat(ai.at("/details/lastFailureAt").asText()).isNotBlank();
        assertThat(ai.toString()).doesNotContain("localhost:1").doesNotContain("apiKey");
        Res ready = api.get("/actuator/health/readiness");
        assertThat(ready.status()).isEqualTo(200);
        assertThat(ready.json().get("status").asText()).isEqualTo("UP");

        // regenerate is the user-forced probe
        fake.reset();
        JsonNode forced = matches(job("Circuit job C"), "regenerate=true");
        assertThat(fake.calls()).isEqualTo(3);
        assertThat(statuses(forced).subList(0, 3)).containsOnly("READY");
        assertThat(api.get("/actuator/health").json().at("/components/ai/status").asText()).isEqualTo("UP");
    }

    // ------------------------------------------------------------------ 2. V3 check constraint

    @Test
    void partialExplanationRowsAreRejectedByTheCheckConstraint() {
        List<UUID> c = fiveCandidates();
        UUID j = job("Constraint job");
        fake.respondText("not json", FinishReason.STOP);
        matches(j);
        String hash = "a".repeat(64);
        Object[] where = {j, c.get(0)};
        String upd = "UPDATE job_match SET ai_explanation = ?, explanation_payload = CAST(? AS jsonb), "
                + "explanation_input_hash = ?, explanation_model = ?, explanation_generated_at = ? "
                + "WHERE job_id = ? AND candidate_id = ?";
        java.sql.Timestamp now = new java.sql.Timestamp(System.currentTimeMillis());
        List<Object[]> bad = List.of(
                new Object[] {"text only", null, null, null, null},
                new Object[] {"t", "{}", null, "m", now},
                new Object[] {" ", "{}", hash, "m", now},
                new Object[] {"t", "[]", hash, "m", now},
                new Object[] {"t", "{}", "A".repeat(64), "m", now},
                new Object[] {"t", "{}", "abc", "m", now},
                new Object[] {"t", "{}", hash, " ", now},
                new Object[] {"t", "{}", hash, "m", null},
                new Object[] {null, "{}", null, null, null});
        for (Object[] b : bad) {
            assertThatThrownBy(() -> jdbc.update(upd, b[0], b[1], b[2], b[3], b[4], where[0], where[1]))
                    .as(java.util.Arrays.toString(b)).isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("ck_job_match_explanation_complete");
        }
        assertThat(jdbc.update(upd, "Full text", "{\"headline\":\"h\"}", hash, "m/x", now, where[0], where[1]))
                .isEqualTo(1);
        assertThat(jdbc.update(upd, null, null, null, null, null, where[0], where[1])).isEqualTo(1);
        assertThat(FakeChatModel.class).isNotNull();
    }
}
