package com.talentmatch.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import com.talentmatch.ai.AiCircuitBreaker;
import com.talentmatch.profile.ResumeExtractionModel;
import com.talentmatch.support.Api.Res;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Base class for AI integration tests (spec §10): AI enabled, the real Ollama bean pointed at a
 * closed port (a missed fake can never reach a real model), {@link FakeChatModel} as the primary
 * ChatModel, short budget/timeout, top-n 3. A separate Spring context (and container) from
 * {@link AbstractApiIT}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "talentmatch.ai.enabled=true",
        "talentmatch.ai.provider=ollama",
        "talentmatch.ai.ollama.base-url=http://localhost:1",
        "talentmatch.ai.request-budget=2s",
        "talentmatch.ai.call-timeout=3s",
        "talentmatch.ai.top-n=3",
        "talentmatch.ai.failure-backoff=60s",
        "talentmatch.ai.regenerate-window=60s"})
@Import({TestcontainersConfiguration.class, FakeChatModelConfig.class})
public abstract class AbstractAiApiIT extends AbstractApiIT {

    @Autowired
    protected FakeChatModel fake;

    @Autowired
    protected MutableClock clock;

    @Autowired
    protected AiCircuitBreaker circuit;

    @Autowired
    @Qualifier("aiExecutor")
    protected ThreadPoolTaskExecutor aiExecutor;

    @Autowired
    protected ResumeExtractionModel extractionModel;

    @Autowired
    @Qualifier("profileExecutor")
    protected ThreadPoolTaskExecutor profileExecutor;

    /** The scripted CV extraction model (Phase 4). */
    protected FakeExtractionModel extractor;

    @BeforeEach
    void resetAi() {
        extractor = (FakeExtractionModel) extractionModel.chatModel();
        extractor.reset();
        fake.reset();
        circuit.recordSuccess(); // a previous test may have opened the shared circuit
    }

    /** Runs after {@link AbstractApiIT}'s truncate: every skill the AI fixtures and tests use. */
    @BeforeEach
    void createSkills() {
        skills("Java", "SQL", "Docker", "Kubernetes", "Git");
    }

    @AfterEach
    void drainAi() {
        fake.releaseAll();
        extractor.releaseAll();
        awaitAiIdle();
        fake.reset();
        extractor.reset();
    }

    /**
     * Waits until no explanation generation and no CV extraction is running or queued (background
     * work persists itself and must not leak into the next test's truncated tables).
     */
    protected void awaitAiIdle() {
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(50)).until(() ->
                idle(aiExecutor) && idle(profileExecutor));
    }

    private static boolean idle(ThreadPoolTaskExecutor executor) {
        return executor.getActiveCount() == 0 && executor.getThreadPoolExecutor().getQueue().isEmpty();
    }

    // ------------------------------------------------------------------ fixtures

    /** Required Java, SQL; nice Docker, Kubernetes. */
    protected UUID backendJob() {
        return backendJob("Backend Engineer");
    }

    /** Same skills as {@link #backendJob()}; a different title avoids the duplicate-job 409. */
    protected UUID backendJob(String title) {
        Res r = api.post("/api/jobs", jobBody(title, "Acme", "Build secret-sauce APIs for payments.",
                "Java", "SQL", "~Docker", "~Kubernetes"));
        assertThat(r.status()).as(r.toString()).isEqualTo(201);
        return UUID.fromString(r.json().get("id").asText());
    }

    protected UUID candidateWithSummary(String name, String email, String summary, String... skills) {
        Res r = api.post("/api/candidates", candidateBody(name, email, summary, skills));
        assertThat(r.status()).as(r.toString()).isEqualTo(201);
        return UUID.fromString(r.json().get("id").asText());
    }

    /**
     * Five candidates with distinct scores: Ada 100%, Bob 83%, Cy 67%, Di 50%, Ed 33%.
     * Returns their ids in rank order.
     */
    protected List<UUID> fiveCandidates() {
        return List.of(
                candidateWithSummary("Ada Lovelace", "ada@example.com", "Ada summary: analytical engines.",
                        "Java:5", "SQL", "Docker", "Kubernetes"),
                candidateWithSummary("Bob Builder", "bob@example.com", "Bob summary: builds things.",
                        "Java:3", "SQL", "Docker"),
                candidateWithSummary("Cy Young", "cy@example.com", "Cy summary: pitcher.", "Java", "SQL"),
                candidateWithSummary("Di Prince", "di@example.com", "Di summary: amazon.", "Java", "Docker"),
                candidateWithSummary("Ed Edison", "ed@example.com", "Ed summary: bulbs.", "Java"));
    }

    protected Res getMatches(UUID jobId, String query) {
        return api.get("/api/jobs/" + jobId + "/matches" + (query == null ? "" : "?" + query));
    }

    protected static JsonNode item(JsonNode page, int index) {
        return page.get("matches").get(index);
    }

    /** The V3 explanation columns for one pair. */
    protected Map<String, Object> explanationRow(UUID jobId, UUID candidateId) {
        return new LinkedHashMap<>(jdbc.queryForMap("SELECT ai_explanation, explanation_payload::text AS payload, "
                + "explanation_input_hash AS hash, explanation_model AS model, explanation_generated_at AS generated_at "
                + "FROM job_match WHERE job_id = ? AND candidate_id = ?", jobId, candidateId));
    }

    protected int storedExplanations(UUID jobId) {
        return jdbc.queryForObject("SELECT count(*) FROM job_match WHERE job_id = ? AND ai_explanation IS NOT NULL",
                Integer.class, jobId);
    }

    /** Asserts aiExplanation != null iff READY iff source AI, for every item. */
    protected static void assertInvariant(JsonNode page) {
        page.get("matches").forEach(m -> {
            boolean ready = "READY".equals(m.get("explanationStatus").asText());
            JsonNode ex = m.get("explanation");
            assertThat(ex).as("explanation always present").isNotNull();
            assertThat(ex.isObject()).isTrue();
            assertThat(!m.get("aiExplanation").isNull()).as("aiExplanation != null iff READY: %s", m).isEqualTo(ready);
            assertThat("AI".equals(ex.get("source").asText())).as("source AI iff READY: %s", m).isEqualTo(ready);
            if (ready) {
                assertThat(m.get("aiExplanation").asText()).isEqualTo(ex.get("text").asText());
                assertThat(ex.get("reason").isNull()).isTrue();
                assertThat(ex.get("note").isNull()).isTrue();
            } else {
                assertThat(ex.get("reason").asText()).isNotBlank();
                assertThat(ex.get("note").asText()).isNotBlank();
                assertThat(ex.get("model").isNull()).isTrue();
            }
        });
    }

    protected static List<String> statuses(JsonNode page) {
        List<String> out = new java.util.ArrayList<>();
        page.get("matches").forEach(m -> out.add(m.get("explanationStatus").asText()));
        return out;
    }

    protected static List<String> reasons(JsonNode page) {
        List<String> out = new java.util.ArrayList<>();
        page.get("matches").forEach(m -> out.add(m.at("/explanation/reason").isNull() ? null
                : m.at("/explanation/reason").asText()));
        return out;
    }
}
