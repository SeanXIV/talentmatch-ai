package com.talentmatch.it;

import static com.talentmatch.support.SeekerFixtures.feed;
import static com.talentmatch.support.SeekerFixtures.feedJob;
import static com.talentmatch.support.SeekerFixtures.jobIds;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.talentmatch.ai.AiCircuitBreaker;
import com.talentmatch.ai.SeekerExplanationPrompts;
import com.talentmatch.support.AbstractAiApiIT;
import com.talentmatch.support.Api.Res;
import com.talentmatch.support.SeekerReplies;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Phase 6 step 4, §9.2 items 5 (MutableClock), 8, 9 and 11 for {@code GET /api/candidates/{id}/matches}:
 * seeker explanations for the top N are generated with the seeker prompt, stored in the V6 seeker
 * columns (recruiter columns untouched), reused, invalidated by the input hash, and a grounding
 * rejection falls back to the template.
 */
class CandidateMatchExplanationIT extends AbstractAiApiIT {

    /** Grounded second-person JSON for seeker prompts; the default fake for recruiter prompts. */
    private void seekerReplies(Function<String, String> explanationForSkill) {
        SeekerReplies.install(fake, explanationForSkill);
    }

    private static String grounded(String skill) {
        return SeekerReplies.grounded(skill);
    }

    private UUID ada;

    @BeforeEach
    void candidate() {
        ada = candidateWithSummary("Ada Lovelace", "ada@example.com", "Ada summary: analytical engines.",
                "Java:5", "SQL");
    }

    private JsonNode seekerPage(UUID candidateId, String query) {
        Res r = api.get("/api/candidates/" + candidateId + "/matches" + (query == null ? "" : "?" + query));
        assertThat(r.status()).as(r.toString()).isEqualTo(200);
        return r.json();
    }

    private Map<String, Object> columns(UUID jobId, UUID candidateId) {
        return jdbc.queryForMap("SELECT ai_explanation, explanation_payload::text AS payload, explanation_input_hash, "
                + "explanation_model, explanation_generated_at, seeker_explanation_payload ->> 'explanation' AS seeker_text, "
                + "seeker_explanation_input_hash, seeker_explanation_model, seeker_explanation_generated_at "
                + "FROM job_match WHERE job_id = ? AND candidate_id = ?", jobId, candidateId);
    }

    private int seekerStored(UUID candidateId) {
        return jdbc.queryForObject("SELECT count(*) FROM job_match WHERE candidate_id = ? "
                + "AND seeker_explanation_payload IS NOT NULL", Integer.class, candidateId);
    }

    private int recruiterStored() {
        return jdbc.queryForObject("SELECT count(*) FROM job_match WHERE ai_explanation IS NOT NULL "
                + "OR explanation_payload IS NOT NULL OR explanation_input_hash IS NOT NULL", Integer.class);
    }

    private long seekerPrompts() {
        return fake.prompts().stream().filter(p -> p.startsWith(SeekerExplanationPrompts.INTRO)).count();
    }

    // ------------------------------------------------------------------ §9.2 item 8

    @Test
    void topNGetReadySeekerExplanationsInTheSeekerColumnsOnly() {
        seekerReplies(CandidateMatchExplanationIT::grounded);
        UUID full = backendJob("Full");                                     // Java, SQL, ~Docker, ~Kubernetes: 20/30
        UUID javaOnly = job("Java Only", "Acme", "Java");                   // 1.0
        UUID sqlGo = job("SQL Git", "Acme", "SQL", "Git");                  // 0.5
        UUID dockerJava = job("Docker Java", "Acme", "Docker", "Java", "Git");
        UUID none = job("Kube", "Acme", "Kubernetes");                      // 0

        JsonNode page = seekerPage(ada, null);
        assertInvariant(page);
        assertThat(page.get("explanationsGenerated").asInt()).isEqualTo(3);
        assertThat(statuses(page)).containsExactly("READY", "READY", "READY", "UNAVAILABLE", "UNAVAILABLE");
        assertThat(reasons(page).subList(3, 5)).containsOnly("NOT_IN_TOP_N");
        assertThat(jobIds(page).get(0)).isEqualTo(javaOnly);
        assertThat(seekerPrompts()).isEqualTo(3);
        assertThat(fake.calls()).isEqualTo(3);

        for (int i = 0; i < 3; i++) {
            JsonNode m = item(page, i);
            UUID jobId = UUID.fromString(m.get("jobId").asText());
            Map<String, Object> row = columns(jobId, ada);
            assertThat(row.get("seeker_text")).as("seeker text stored").isEqualTo(m.get("aiExplanation").asText());
            assertThat(m.get("aiExplanation").asText()).startsWith("You bring ");
            assertThat(row.get("seeker_explanation_input_hash")).isNotNull();
            assertThat(row.get("seeker_explanation_model")).isEqualTo(m.at("/explanation/model").asText());
            assertThat(row.get("seeker_explanation_generated_at")).isNotNull();
            assertThat(row.get("ai_explanation")).as("recruiter columns untouched").isNull();
            assertThat(row.get("payload")).isNull();
            assertThat(row.get("explanation_input_hash")).isNull();
            assertThat(m.at("/explanation/headline").asText()).isEqualTo("A good fit for you");
        }
        assertThat(seekerStored(ada)).isEqualTo(3);
        assertThat(recruiterStored()).isZero();
        for (int i = 3; i < 5; i++) {
            assertThat(item(page, i).at("/explanation/source").asText()).isEqualTo("TEMPLATE");
            assertThat(item(page, i).at("/explanation/text").asText()).doesNotContain("Ada");
        }

        // the prompt: second person, no name or email, the profile summary inside its tag
        for (String p : fake.prompts()) {
            assertThat(p).startsWith(SeekerExplanationPrompts.INTRO).doesNotContain("Ada Lovelace")
                    .doesNotContain("Lovelace").doesNotContain("ada@example.com").doesNotContain("Candidate:")
                    .contains("<profile_summary>").contains("Ada summary: analytical engines.")
                    .contains("</profile_summary>").contains("Matched required skills: ");
        }

        // second GET: no model calls, still READY
        JsonNode again = seekerPage(ada, null);
        assertThat(fake.calls()).isEqualTo(3);
        assertThat(again.get("explanationsGenerated").asInt()).isZero();
        assertThat(statuses(again)).containsExactly("READY", "READY", "READY", "UNAVAILABLE", "UNAVAILABLE");
        assertThat(again.get("recomputedJobs").asInt()).isZero();

        // the job side writes the recruiter explanation for the same pair; neither invalidates the other
        JsonNode recruiter = getMatches(javaOnly, null).json();
        assertThat(item(recruiter, 0).get("explanationStatus").asText()).isEqualTo("READY");
        assertThat(fake.calls()).isEqualTo(4);
        Map<String, Object> both = columns(javaOnly, ada);
        assertThat(both.get("ai_explanation")).isNotNull();
        assertThat(both.get("seeker_text")).isNotNull().isNotEqualTo(both.get("ai_explanation"));
        JsonNode seekerAfter = seekerPage(ada, null);
        assertThat(statuses(seekerAfter).subList(0, 3)).containsOnly("READY");
        assertThat(item(getMatches(javaOnly, null).json(), 0).get("explanationStatus").asText()).isEqualTo("READY");
        assertThat(fake.calls()).as("no ping-pong").isEqualTo(4);
        assertThat(List.of(full, sqlGo, dockerJava, none)).doesNotContainNull();
    }

    // ------------------------------------------------------------------ §9.2 item 11: grounding fallbacks

    private void assertRejected(String explanation) {
        seekerReplies(skill -> explanation);
        UUID job = job("Java SQL", "Acme", "Java", "SQL");
        JsonNode page = seekerPage(ada, null);
        JsonNode m = item(page, 0);
        assertThat(fake.calls()).isEqualTo(1);
        assertThat(m.get("explanationStatus").asText()).isEqualTo("UNAVAILABLE");
        assertThat(m.at("/explanation/reason").asText()).isEqualTo("GENERATION_FAILED");
        assertThat(m.at("/explanation/source").asText()).isEqualTo("TEMPLATE");
        assertThat(m.get("aiExplanation").isNull()).isTrue();
        assertThat(page.get("explanationsGenerated").asInt()).isZero();
        assertThat(columns(job, ada).get("seeker_text")).as("nothing stored").isNull();
        assertThat(seekerStored(ada)).isZero();
        assertThat(circuit.state()).isEqualTo(AiCircuitBreaker.State.CLOSED);
        assertInvariant(page);
    }

    @Test
    void aSkillNotOnTheJobFallsBackToTheTemplate() {
        assertRejected("You bring Java and Kubernetes, which this role requires.");
    }

    @Test
    void anInventedNumberFallsBackToTheTemplate() {
        assertRejected("You bring Java with 12 years of experience, which this role requires.");
    }

    @Test
    void aGroundedNumberIsAccepted() {
        seekerReplies(skill -> "You bring " + skill + " with 5 years of experience, a 100% fit for this role.");
        job("Java SQL", "Acme", "Java", "SQL");
        JsonNode page = seekerPage(ada, null);
        assertThat(item(page, 0).get("explanationStatus").asText()).isEqualTo("READY");
    }

    // ------------------------------------------------------------------ §9.2 item 9: invalidation

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

    @Test
    void identicalProfileSaveKeepsScoresAndExplanationsAndAChangedSummaryRegenerates() {
        seekerReplies(CandidateMatchExplanationIT::grounded);
        Res saved = api.put("/api/profile", profileBody("Backend Engineer").toString());
        assertThat(saved.status()).as(saved.toString()).isEqualTo(200);
        UUID owner = UUID.fromString(saved.json().get("candidateId").asText());
        UUID a = job("Java Only", "Acme", "Java");
        job("SQL Only", "Acme", "SQL");

        JsonNode first = seekerPage(owner, null);
        assertThat(first.get("owner").asBoolean()).isTrue();
        assertThat(first.get("profileVersion").asInt()).isEqualTo(1);
        assertThat(first.get("recomputedJobs").asInt()).isEqualTo(2);
        assertThat(statuses(first)).containsOnly("READY");
        assertThat(fake.calls()).isEqualTo(2);
        for (String p : fake.prompts()) {
            assertThat(p).doesNotContain("Olive").doesNotContain("olive@example.com").contains("Backend Engineer");
        }
        Instant updated = updatedAt("candidate", owner);

        // identical save: no rescoring, explanations stay READY, no model call
        Res same = api.put("/api/profile", profileBody("Backend Engineer").toString());
        assertThat(same.status()).as(same.toString()).isEqualTo(200);
        assertThat(updatedAt("candidate", owner)).as("candidate not touched").isEqualTo(updated);
        JsonNode afterSame = seekerPage(owner, null);
        assertThat(afterSame.get("recomputedJobs").asInt()).isZero();
        assertThat(statuses(afterSame)).containsOnly("READY");
        assertThat(afterSame.get("explanationsGenerated").asInt()).isZero();
        assertThat(fake.calls()).isEqualTo(2);
        assertThat(afterSame.get("profileVersion").asInt()).isEqualTo(same.json().get("version").asInt());

        // a changed summary: everything in scope is rescored and the explanations are regenerated
        Res changed = api.put("/api/profile", profileBody("Staff Engineer").toString());
        assertThat(changed.status()).as(changed.toString()).isEqualTo(200);
        JsonNode afterChange = seekerPage(owner, null);
        assertThat(afterChange.get("recomputedJobs").asInt()).isEqualTo(2);
        assertThat(statuses(afterChange)).containsOnly("READY");
        assertThat(afterChange.get("explanationsGenerated").asInt()).isEqualTo(2);
        assertThat(fake.calls()).isEqualTo(4);
        assertThat(fake.prompts().get(3)).contains("Staff Engineer");
        assertThat(columns(a, owner).get("seeker_text")).isNotNull();
    }

    @Test
    void aJobSkillChangeRescoresOneJobAndRegeneratesItsExplanation() {
        seekerReplies(CandidateMatchExplanationIT::grounded);
        UUID a = job("Java Only", "Acme", "Java");
        job("SQL Only", "Acme", "SQL");
        seekerPage(ada, null);
        assertThat(fake.calls()).isEqualTo(2);
        String hashBefore = (String) columns(a, ada).get("seeker_explanation_input_hash");

        Res put = api.put("/api/jobs/" + a, jobBody("Java Only", "Acme", null, "Java", "~Docker"));
        assertThat(put.status()).as(put.toString()).isEqualTo(200);
        JsonNode page = seekerPage(ada, null);
        assertThat(page.get("recomputedJobs").asInt()).isEqualTo(1);
        assertThat(page.get("explanationsGenerated").asInt()).isEqualTo(1);
        assertThat(statuses(page)).containsOnly("READY");
        assertThat(fake.calls()).isEqualTo(3);
        assertThat(columns(a, ada).get("seeker_explanation_input_hash")).isNotEqualTo(hashBefore);
    }

    @Test
    void aCandidateEditRescoresEveryJobInScope() {
        seekerReplies(CandidateMatchExplanationIT::grounded);
        job("A", "Acme", "Java");
        job("B", "Acme", "SQL");
        job("C", "Acme", "Docker");
        assertThat(seekerPage(ada, null).get("recomputedJobs").asInt()).isEqualTo(3);
        Res edit = api.put("/api/candidates/" + ada, candidateBody("Ada Lovelace", "ada@example.com",
                "Ada summary: analytical engines.", "Java:5", "SQL", "Docker"));
        assertThat(edit.status()).as(edit.toString()).isEqualTo(200);
        JsonNode page = seekerPage(ada, null);
        assertThat(page.get("recomputedJobs").asInt()).isEqualTo(3);
        assertThat(page.get("matches").get(0).get("score").asDouble()).isEqualTo(1.0);
    }

    // ------------------------------------------------------------------ §9.2 item 5: postedSince vs the clock

    @Test
    void postedSinceDurationsFollowTheApplicationClock() {
        seekerReplies(CandidateMatchExplanationIT::grounded);
        Instant now = clock.instant();
        UUID recent = feedJob(jdbc, "Recent", "Acme", feed().posted(now.minus(Duration.ofHours(36)))
                .firstSeen(now.minus(Duration.ofHours(30))), "Java");
        feedJob(jdbc, "Old", "Acme", feed().posted(now.minus(Duration.ofDays(4))).firstSeen(now.minus(Duration.ofDays(4))),
                "Java");
        assertThat(jobIds(seekerPage(ada, "postedSince=P2D"))).containsExactly(recent);
        clock.advance(Duration.ofDays(1));
        try {
            JsonNode later = seekerPage(ada, "postedSince=P2D");
            assertThat(later.get("matches")).isEmpty();
            assertThat(later.get("reason").asText()).isEqualTo("NO_MATCHABLE_JOBS");
            assertThat(jobIds(seekerPage(ada, "postedSince=P3D"))).containsExactly(recent);
        } finally {
            clock.advance(Duration.ofDays(-1));
        }
    }
}
