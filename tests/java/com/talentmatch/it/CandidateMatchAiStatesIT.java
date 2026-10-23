package com.talentmatch.it;

import static com.talentmatch.support.SeekerFixtures.feed;
import static com.talentmatch.support.SeekerFixtures.feedJob;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.talentmatch.ai.AiCircuitBreaker;
import com.talentmatch.ai.LocalModelGate;
import com.talentmatch.support.AbstractAiApiIT;
import com.talentmatch.support.Api.Res;
import com.talentmatch.support.SeekerReplies;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Phase 6 §9.2 items 9 (feed description change), 10 (PENDING and the guarded write) and 12 (AI
 * states: AI_BUSY, PROVIDER_UNAVAILABLE and the circuit shared with the recruiter side) for
 * {@code GET /api/candidates/{id}/matches}. Uses the AI context's 2s request budget (the spec
 * suggests 1s; 2s avoids a separate Spring context and container).
 */
class CandidateMatchAiStatesIT extends AbstractAiApiIT {

    @Autowired
    private LocalModelGate gate;

    private JsonNode seekerPage(UUID candidateId) {
        Res r = api.get("/api/candidates/" + candidateId + "/matches");
        assertThat(r.status()).as(r.toString()).isEqualTo(200);
        return r.json();
    }

    private UUID ada() {
        return candidateWithSummary("Ada Lovelace", "ada@example.com", "Ada summary: analytical engines.",
                "Java:5", "SQL");
    }

    private String seekerHash(UUID jobId, UUID candidateId) {
        return jdbc.queryForObject("SELECT seeker_explanation_input_hash FROM job_match WHERE job_id = ? "
                + "AND candidate_id = ?", String.class, jobId, candidateId);
    }

    private int seekerStored(UUID candidateId) {
        return jdbc.queryForObject("SELECT count(*) FROM job_match WHERE candidate_id = ? "
                + "AND seeker_explanation_payload IS NOT NULL", Integer.class, candidateId);
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

    // ------------------------------------------------------------------ §9.2 item 9: feed description

    @Test
    void aFeedDescriptionChangeRegeneratesTheSeekerExplanation() {
        SeekerReplies.install(fake);
        UUID ada = ada();
        UUID j = feedJob(jdbc, "Feed Java", "Acme", feed().description("Original feed description."), "Java");
        JsonNode first = seekerPage(ada);
        assertThat(statuses(first)).containsExactly("READY");
        assertThat(fake.calls()).isEqualTo(1);
        String before = seekerHash(j, ada);
        assertThat(before).isNotNull();

        // what FeedProcessor does on a changed posting: the job's description is rewritten
        jdbc.update("UPDATE job SET description = ? WHERE id = ?", "Rewritten feed description.", j);
        JsonNode next = seekerPage(ada);
        assertThat(statuses(next)).containsExactly("READY");
        assertThat(next.get("explanationsGenerated").asInt()).isEqualTo(1);
        assertThat(fake.calls()).isEqualTo(2);
        assertThat(fake.prompts().get(1)).contains("Rewritten feed description.")
                .doesNotContain("Original feed description.");
        assertThat(seekerHash(j, ada)).isNotNull().isNotEqualTo(before);

        // and it is reused afterwards
        assertThat(statuses(seekerPage(ada))).containsExactly("READY");
        assertThat(fake.calls()).isEqualTo(2);
    }

    // ------------------------------------------------------------------ §9.2 item 10: PENDING

    @Test
    void blockedModelGivesPendingGeneratingThenReadyWithoutAnotherCall() {
        UUID ada = ada();
        job("Java Only", "Acme", "Java");
        job("SQL Only", "Acme", "SQL");
        CountDownLatch latch = SeekerReplies.installBlocking(fake);
        try {
            JsonNode page = seekerPage(ada);
            assertThat(statuses(page)).containsOnly("PENDING");
            assertThat(reasons(page)).containsOnly("GENERATING");
            assertThat(page.get("explanationsGenerated").asInt()).isZero();
            assertInvariant(page);
            for (JsonNode m : page.get("matches")) {
                assertThat(m.at("/explanation/source").asText()).isEqualTo("TEMPLATE");
                assertThat(m.at("/explanation/text").asText()).doesNotContain("Ada");
            }
        } finally {
            latch.countDown();
        }
        awaitAiIdle();
        assertThat(fake.calls()).isEqualTo(2);
        assertThat(seekerStored(ada)).isEqualTo(2);

        JsonNode next = seekerPage(ada);
        assertThat(statuses(next)).containsOnly("READY");
        assertThat(next.get("explanationsGenerated").asInt()).isZero();
        assertThat(fake.calls()).as("the background results are reused").isEqualTo(2);
        assertInvariant(next);
    }

    @Test
    void aProfileSaveWhileGeneratingDiscardsTheResultAndTheNextGetRegenerates() {
        Res saved = api.put("/api/profile", profileBody("Backend Engineer").toString());
        assertThat(saved.status()).as(saved.toString()).isEqualTo(200);
        UUID owner = UUID.fromString(saved.json().get("candidateId").asText());
        UUID j = job("Java Only", "Acme", "Java");
        CountDownLatch latch = SeekerReplies.installBlocking(fake);
        try {
            JsonNode page = seekerPage(owner);
            assertThat(statuses(page)).containsExactly("PENDING");
            Res changed = api.put("/api/profile", profileBody("Staff Engineer").toString());
            assertThat(changed.status()).as(changed.toString()).isEqualTo(200);
        } finally {
            latch.countDown();
        }
        awaitAiIdle();
        assertThat(fake.calls()).isEqualTo(1);
        assertThat(seekerHash(j, owner)).as("guarded write saw the profile change").isNull();
        assertThat(seekerStored(owner)).isZero();

        SeekerReplies.install(fake);
        JsonNode next = seekerPage(owner);
        assertThat(statuses(next)).containsExactly("READY");
        assertThat(next.get("explanationsGenerated").asInt()).isEqualTo(1);
        assertThat(fake.calls()).isEqualTo(2);
        assertThat(fake.prompts().get(1)).contains("Staff Engineer");
        assertThat(seekerHash(j, owner)).isNotNull();
    }

    // ------------------------------------------------------------------ §9.2 item 12: AI states

    @Test
    void anExtractionHoldingTheLocalModelGivesAiBusyWithoutCallsOrCircuitChange() throws Exception {
        SeekerReplies.install(fake);
        UUID ada = ada();
        job("Java Only", "Acme", "Java");
        job("SQL Only", "Acme", "SQL");
        gate.acquireExclusive();
        try {
            JsonNode page = seekerPage(ada);
            assertThat(statuses(page)).containsOnly("UNAVAILABLE");
            assertThat(reasons(page)).containsOnly("AI_BUSY");
            assertInvariant(page);
            assertThat(fake.calls()).isZero();
            assertThat(circuit.state()).isEqualTo(AiCircuitBreaker.State.CLOSED);
        } finally {
            gate.releaseExclusive();
        }
        // AI_BUSY sets no backoff: the next GET generates
        JsonNode after = seekerPage(ada);
        assertThat(statuses(after)).containsOnly("READY");
        assertThat(fake.calls()).isEqualTo(2);
    }

    @Test
    void threeProviderFailuresGiveProviderUnavailableAndOpenTheCircuitSharedWithTheRecruiterSide() {
        UUID ada = ada();
        job("A", "Acme", "Java");
        job("B", "Acme", "SQL");
        job("C", "Acme", "Java", "SQL");
        UUID recruiterJob = backendJob("Recruiter side");
        fake.fail(new IllegalStateException("connection refused"));

        JsonNode page = seekerPage(ada);
        assertThat(fake.calls()).isEqualTo(3);
        assertThat(statuses(page).subList(0, 3)).containsOnly("UNAVAILABLE");
        assertThat(reasons(page).subList(0, 3)).containsOnly("PROVIDER_UNAVAILABLE");
        assertThat(page.get("explanationsGenerated").asInt()).isZero();
        assertThat(seekerStored(ada)).isZero();
        assertInvariant(page);
        assertThat(circuit.state()).isEqualTo(AiCircuitBreaker.State.OPEN);
        Res health = api.get("/actuator/health");
        assertThat(health.json().at("/components/ai/details/circuit").asText()).isEqualTo("OPEN");

        // the recruiter side shares the circuit: no model call, PROVIDER_UNAVAILABLE
        Res recruiter = getMatches(recruiterJob, null);
        assertThat(recruiter.status()).as(recruiter.toString()).isEqualTo(200);
        assertThat(fake.calls()).as("circuit open for the recruiter audience too").isEqualTo(3);
        assertThat(item(recruiter.json(), 0).at("/explanation/reason").asText()).isEqualTo("PROVIDER_UNAVAILABLE");
    }
}
