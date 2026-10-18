package com.talentmatch.it;

import static com.talentmatch.support.SeekerFixtures.candidateWithId;
import static com.talentmatch.support.SeekerFixtures.feed;
import static com.talentmatch.support.SeekerFixtures.feedJob;
import static com.talentmatch.support.SeekerFixtures.holdCandidateLock;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.talentmatch.support.AbstractAiApiIT;
import com.talentmatch.support.Api.Res;
import com.talentmatch.support.SeekerReplies;
import java.sql.Connection;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * Phase 6 step 5, §9.2 item 13 and §5.3 with AI enabled: {@code regenerate=true} on the candidate side
 * rescores every in-scope matchable job and regenerates the top-N seeker explanations; it is limited
 * per candidate (429 with the candidate message), {@code /api/profile/matches} and
 * {@code /api/candidates/{ownerId}/matches} share the slot, the job scope is separate, and a request that
 * fails (400, 404, 503) does not keep the slot.
 */
@ExtendWith(OutputCaptureExtension.class)
class CandidateMatchRegenerateIT extends AbstractAiApiIT {

    private UUID ada;

    @BeforeEach
    void data() {
        SeekerReplies.install(fake);
        ada = candidateWithSummary("Ada Lovelace", "ada@example.com", "PRIVATE-SUMMARY-TEXT analytical engines.",
                "Java:5", "SQL");
    }

    private Res cand(UUID id, String query) {
        return api.get("/api/candidates/" + id + "/matches" + (query == null ? "" : "?" + query));
    }

    private Res profile(String query) {
        return api.get("/api/profile/matches" + (query == null ? "" : "?" + query));
    }

    private static JsonNode ok(Res r) {
        assertThat(r.status()).as(r.toString()).isEqualTo(200);
        return r.json();
    }

    /** 429 with Retry-After and the exact §5.3 candidate message; returns the body. */
    private static JsonNode assertCandidateLimited(Res r, String path) {
        JsonNode body = assertError(r, 429, "REGENERATE_RATE_LIMITED");
        int retry = Integer.parseInt(r.header("Retry-After"));
        assertThat(retry).isBetween(1, 60);
        assertThat(body.get("message").asText()).isEqualTo("Matches for this candidate were regenerated recently. "
                + "You can regenerate again in " + retry + (retry == 1 ? " second" : " seconds")
                + "; reload without regenerate=true to see the current results.");
        assertThat(body.get("error").asText()).isEqualTo("Too Many Requests");
        assertThat(body.get("path").asText()).isEqualTo(path);
        return body;
    }

    private ObjectNode profileBody() {
        ObjectNode p = mapper.createObjectNode();
        p.put("fullName", "Olive Owner");
        p.put("email", "olive@example.com");
        p.put("headline", "Backend Engineer");
        ArrayNode skills = p.putArray("skills");
        skills.addObject().put("name", "Java").put("years", 5);
        skills.addObject().put("name", "SQL");
        ObjectNode body = mapper.createObjectNode();
        body.set("profile", p);
        return body;
    }

    private UUID saveProfile() {
        Res saved = api.put("/api/profile", profileBody().toString());
        assertThat(saved.status()).as(saved.toString()).isEqualTo(200);
        return UUID.fromString(saved.json().get("candidateId").asText());
    }

    /** Advances the shared clock past the 60s window and restores it afterwards. */
    private void afterWindow(Runnable r) {
        clock.advance(Duration.ofSeconds(61));
        try {
            r.run();
        } finally {
            clock.advance(Duration.ofSeconds(-61));
        }
    }

    // ------------------------------------------------------------------ §9.2 item 13

    @Test
    void regenerateRescoresInScopeJobsRegeneratesExplanationsAndIsLimitedPerCandidate() {
        job("Java Only", "Acme", "Java");
        job("SQL Only", "Acme", "SQL");
        job("Both", "Acme", "Java", "SQL");
        job("Go Docker", "Acme", "Docker");
        jobWithoutSkills("Skill-less", "Acme");
        feedJob(jdbc, "Closed", "Acme", feed().closed(java.time.Instant.now()), "Java");

        JsonNode first = ok(cand(ada, null));
        assertThat(first.get("recomputedJobs").asInt()).isEqualTo(4);
        assertThat(first.get("explanationsGenerated").asInt()).isEqualTo(3);
        assertThat(statuses(first).subList(0, 3)).containsOnly("READY");
        assertThat(fake.calls()).isEqualTo(3);
        assertThat(ok(cand(ada, null)).get("explanationsGenerated").asInt()).isZero();

        JsonNode regen = ok(cand(ada, "regenerate=true"));
        assertThat(regen.get("recomputedJobs").asInt()).as("every in-scope matchable job").isEqualTo(4);
        assertThat(regen.get("explanationsGenerated").asInt()).as("READY items regenerated").isEqualTo(3);
        assertThat(statuses(regen).subList(0, 3)).containsOnly("READY");
        assertThat(fake.calls()).isEqualTo(6);
        assertInvariant(regen);

        assertCandidateLimited(cand(ada, "regenerate=true"), "/api/candidates/" + ada + "/matches");
        // other parameters don't make a new slot
        assertCandidateLimited(cand(ada, "regenerate=true&origin=manual&sort=newest"),
                "/api/candidates/" + ada + "/matches");
        assertThat(fake.calls()).isEqualTo(6);
        // a plain GET is never limited
        assertThat(ok(cand(ada, null)).get("recomputedJobs").asInt()).isZero();
        // another candidate has its own slot
        UUID bob = candidateWithSummary("Bob Builder", "bob@example.com", null, "Java");
        assertThat(ok(cand(bob, "regenerate=true")).get("recomputedJobs").asInt()).isEqualTo(4);
        // a narrower scope rescores only that scope
        afterWindow(() -> {
            JsonNode narrow = ok(cand(ada, "regenerate=true&origin=feed&includeClosed=true"));
            assertThat(narrow.get("recomputedJobs").asInt()).isEqualTo(1);
        });
    }

    @Test
    void jobAndCandidateScopesWithTheSameUuidDoNotBlockEachOther() {
        UUID shared = job("Backend", "Acme", "Java", "SQL");
        candidateWithId(jdbc, shared, "Same Id", "same@example.com", "Java:2");
        job("Other", "Acme", "SQL");

        assertThat(getMatches(shared, "regenerate=true").status()).isEqualTo(200);
        JsonNode c = ok(cand(shared, "regenerate=true"));
        assertThat(c.get("candidateId").asText()).isEqualTo(shared.toString());
        assertThat(c.get("recomputedJobs").asInt()).isEqualTo(2);

        assertCandidateLimited(cand(shared, "regenerate=true"), "/api/candidates/" + shared + "/matches");
        JsonNode jobLimited = assertError(getMatches(shared, "regenerate=true"), 429, "REGENERATE_RATE_LIMITED");
        assertThat(jobLimited.get("message").asText()).startsWith("Matches for this job were regenerated recently.");

        // the reverse order on a fresh pair
        UUID shared2 = job("Platform", "Acme", "Java");
        candidateWithId(jdbc, shared2, "Same Id Two", "same2@example.com", "SQL");
        assertThat(cand(shared2, "regenerate=true").status()).isEqualTo(200);
        assertThat(getMatches(shared2, "regenerate=true").status()).isEqualTo(200);
    }

    @Test
    void profileAndOwnerCandidatePathShareOneSlot() {
        UUID owner = saveProfile();
        job("Java Only", "Acme", "Java");

        JsonNode viaProfile = ok(profile("regenerate=true"));
        assertThat(viaProfile.get("owner").asBoolean()).isTrue();
        assertThat(viaProfile.get("recomputedJobs").asInt()).isEqualTo(1);
        assertCandidateLimited(cand(owner, "regenerate=true"), "/api/candidates/" + owner + "/matches");
        assertCandidateLimited(profile("regenerate=true"), "/api/profile/matches");
        assertThat(ok(profile(null)).get("recomputedJobs").asInt()).isZero();

        afterWindow(() -> {
            assertThat(ok(cand(owner, "regenerate=true")).get("recomputedJobs").asInt()).isEqualTo(1);
            assertCandidateLimited(profile("regenerate=true"), "/api/profile/matches");
        });
    }

    @Test
    void failedRequestsDoNotKeepTheSlot() {
        job("Java Only", "Acme", "Java");
        // 404: an unknown candidate, twice; then the same id exists and gets its slot
        UUID missing = UUID.randomUUID();
        assertError(cand(missing, "regenerate=true"), 404, "CANDIDATE_NOT_FOUND");
        assertError(cand(missing, "regenerate=true"), 404, "CANDIDATE_NOT_FOUND");
        candidateWithId(jdbc, missing, "Late Comer", "late@example.com", "Java");
        assertThat(ok(cand(missing, "regenerate=true")).get("recomputedJobs").asInt()).isEqualTo(1);
        assertCandidateLimited(cand(missing, "regenerate=true"), "/api/candidates/" + missing + "/matches");

        // 400: parameter validation runs before the limiter
        assertError(cand(ada, "regenerate=true&limit=0"), 400, "INVALID_PARAMETER");
        assertError(cand(ada, "regenerate=true&includeFiltered=maybe"), 400, "INVALID_PARAMETER");
        assertThat(cand(ada, "regenerate=true").status()).isEqualTo(200);

        // PROFILE_NOT_FOUND, repeatedly, then the profile exists
        assertError(profile("regenerate=true"), 404, "PROFILE_NOT_FOUND");
        assertError(profile("regenerate=true"), 404, "PROFILE_NOT_FOUND");
        assertError(profile("regenerate=true&sort=bad"), 400, "INVALID_PARAMETER");
        UUID owner = saveProfile();
        assertThat(ok(profile("regenerate=true")).get("candidateId").asText()).isEqualTo(owner.toString());
    }

    @Test
    void matchesBusyDoesNotKeepTheSlotAndUsesTheCandidateMessage() throws Exception {
        UUID owner = saveProfile();
        job("Java Only", "Acme", "Java");
        try (Connection c = rawConnection()) {
            holdCandidateLock(c, owner);
            Res busy = profile("regenerate=true");
            JsonNode err = assertError(busy, 503, "MATCHES_BUSY");
            assertThat(busy.header("Retry-After")).isEqualTo("2");
            assertThat(err.get("message").asText()).isEqualTo(CandidateOwnerMatchesIT.CANDIDATE_BUSY);
        }
        assertThat(fake.calls()).isZero();
        JsonNode after = ok(cand(owner, "regenerate=true"));
        assertThat(after.get("recomputedJobs").asInt()).isEqualTo(1);
        assertThat(after.get("explanationsGenerated").asInt()).isEqualTo(1);
        assertCandidateLimited(profile("regenerate=true"), "/api/profile/matches");
    }

    @Test
    void regenerateLogsCarryNoFreeText(CapturedOutput output) {
        job("Backend", "Acme", "Java");
        int mark = output.getAll().length();
        JsonNode page = ok(cand(ada, "regenerate=true&location=Secretville"));
        assertThat(page.get("matches")).isEmpty();
        UUID grace = candidateWithSummary("Grace Hopper", "grace@example.com", "PRIVATE-SUMMARY-TWO", "Java");
        JsonNode full = ok(cand(grace, "regenerate=true"));
        assertThat(statuses(full)).containsOnly("READY");
        assertCandidateLimited(cand(ada, "regenerate=true"), "/api/candidates/" + ada + "/matches");
        String logs = output.getAll().substring(mark);
        assertThat(logs).contains("Candidate matches candidate=" + ada + " owner=false sort=SCORE");
        assertThat(logs).doesNotContain("Secretville").doesNotContain("PRIVATE-SUMMARY").doesNotContain("You bring")
                .doesNotContain("Grace Hopper").doesNotContain("Ada Lovelace").doesNotContain("grace@example.com");
    }
}
