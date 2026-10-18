package com.talentmatch.it;

import static com.talentmatch.support.SeekerFixtures.feed;
import static com.talentmatch.support.SeekerFixtures.feedJob;
import static com.talentmatch.support.SeekerFixtures.holdCandidateLock;
import static com.talentmatch.support.SeekerFixtures.holdJobLock;
import static com.talentmatch.support.SeekerFixtures.jobIds;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.talentmatch.support.AbstractApiIT;
import com.talentmatch.support.Api.Res;
import java.sql.Connection;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * Phase 6 step 5 with AI disabled (decisions a, e, f; §5.1, §5.3, §12.5): {@code GET /api/profile/matches},
 * owner-only preference fields and {@code includeFiltered}, the computed LOCATION_UNKNOWN flag,
 * {@code regenerate} without a rate limit, and the candidate-side MATCHES_BUSY message.
 */
@ExtendWith(OutputCaptureExtension.class)
class CandidateOwnerMatchesIT extends AbstractApiIT {

    static final String CANDIDATE_BUSY = "Matches for this candidate are being recalculated by another request. "
            + "Please try again in a few seconds.";
    static final String JOB_BUSY = "Matches for this job are being recalculated by another request. Please try "
            + "again in a few seconds.";

    private UUID other;

    @BeforeEach
    void data() {
        skills("Java", "SQL", "Docker", "Go");
        other = candidate("Bob Builder", "bob@example.com", "Java:5", "SQL");
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

    private UUID saveProfile() {
        Res saved = api.put("/api/profile", profileBody("PRIVATE-HEADLINE-TEXT engineer").toString());
        assertThat(saved.status()).as(saved.toString()).isEqualTo(200);
        return UUID.fromString(saved.json().get("candidateId").asText());
    }

    private Res profile(String query) {
        return api.get("/api/profile/matches" + (query == null ? "" : "?" + query));
    }

    private Res cand(UUID id, String query) {
        return api.get("/api/candidates/" + id + "/matches" + (query == null ? "" : "?" + query));
    }

    private static JsonNode ok(Res r) {
        assertThat(r.status()).as(r.toString()).isEqualTo(200);
        return r.json();
    }

    private static JsonNode item(JsonNode page, UUID jobId) {
        for (JsonNode m : page.get("matches")) {
            if (m.get("jobId").asText().equals(jobId.toString())) {
                return m;
            }
        }
        throw new AssertionError("job " + jobId + " not in " + page);
    }

    private int rows(UUID jobId, UUID candidateId) {
        return jdbc.queryForObject("SELECT count(*) FROM job_match WHERE job_id = ? AND candidate_id = ?", Integer.class,
                jobId, candidateId);
    }

    private static void assertNoPreferenceFields(JsonNode m) {
        assertThat(m.get("preferenceVerdict")).isNotNull();
        assertThat(m.get("preferenceVerdict").isNull()).as("preferenceVerdict null: %s", m).isTrue();
        assertThat(m.get("filterReasons").isArray()).isTrue();
        assertThat(m.get("filterReasons")).isEmpty();
        assertThat(m.get("flags").isArray()).isTrue();
        assertThat(m.get("flags")).isEmpty();
    }

    // ------------------------------------------------------------------ decision a: /api/profile/matches

    @Test
    void profileMatchesWithoutAProfileIs404AndBadParamsAre400First() {
        job("Backend", "Acme", "Java");
        JsonNode err = assertError(profile(null), 404, "PROFILE_NOT_FOUND");
        assertThat(err.get("path").asText()).isEqualTo("/api/profile/matches");
        assertError(profile("regenerate=true"), 404, "PROFILE_NOT_FOUND");
        assertError(profile("sort=newest&includeFiltered=true&country=ZA"), 404, "PROFILE_NOT_FOUND");
        // parameters are validated before the profile lookup
        JsonNode bad = assertError(profile("sort=date"), 400, "INVALID_PARAMETER");
        assertThat(fields(bad)).containsExactly("sort");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM job_match", Integer.class)).isZero();
    }

    @Test
    void profileMatchesEqualTheOwnersCandidateMatches() {
        UUID owner = saveProfile();
        job("Full", "Acme", "Java", "SQL");
        job("Half", "Acme", "Java", "Go");
        feedJob(jdbc, "Feed ZA", "Acme", feed().workplace("ONSITE").countries("ZA").verdict("PASS", "[]"), "SQL");
        feedJob(jdbc, "Feed Nowhere", "Acme", feed().workplace("HYBRID").verdict("PASS", "[]")
                .flags("[\"SALARY_OTHER_CURRENCY\"]"), "Java", "~Docker");
        feedJob(jdbc, "Filtered", "Acme", feed().verdict("FILTERED", "[\"SENIORITY\"]"), "Java");
        jobWithoutSkills("Skill-less", "Acme");

        JsonNode first = ok(profile(null));
        assertThat(first.get("candidateId").asText()).isEqualTo(owner.toString());
        assertThat(first.get("owner").asBoolean()).isTrue();
        assertThat(first.get("profileVersion").asInt()).isEqualTo(1);
        assertThat(first.get("recomputedJobs").asInt()).isEqualTo(4);
        // the FILTERED job is scored only once a request includes it
        assertThat(ok(profile("includeFiltered=true")).get("recomputedJobs").asInt()).isEqualTo(1);

        for (String q : new String[] {null, "sort=newest", "includeFiltered=true", "country=ZA&limit=2&page=1",
                "minScore=0.5&origin=feed", "includeClosed=true&remote=exclude", "regenerate=false"}) {
            JsonNode viaProfile = ok(profile(q));
            JsonNode viaCandidate = ok(cand(owner, q));
            assertThat(viaProfile).as("query %s", q).isEqualTo(viaCandidate);
        }
    }

    @Test
    void profileMatchesParameterErrorsHaveTheFullShape() {
        saveProfile();
        String[][] cases = {
            {"limit=0", "limit", "limit must be between 1 and 100."},
            {"sort=date", "sort", "sort must be score or newest."},
            {"country=XX", "country", "'XX' is not an ISO country code."},
            {"remote=yes", "remote", "remote must be include, only or exclude."},
            {"origin=etl", "origin", "origin must be all, feed or manual."},
            {"location=a", "location", "location must be 2 to 100 characters."},
            {"includeFiltered=maybe", "includeFiltered", "Parameter 'includeFiltered' must be true or false."},
            {"regenerate=yes", "regenerate", "Parameter 'regenerate' must be true or false."},
            {"includeClosed=1", "includeClosed", "Parameter 'includeClosed' must be true or false."}};
        for (String[] c : cases) {
            JsonNode err = assertError(profile(c[0]), 400, "INVALID_PARAMETER");
            assertThat(fields(err)).as(c[0]).containsExactly(c[1]);
            assertThat(err.get("message").asText()).as(c[0]).isEqualTo(c[2]);
            assertThat(err.get("path").asText()).isEqualTo("/api/profile/matches");
        }
        // the candidate path binds the two new booleans strictly too
        JsonNode err = assertError(cand(other, "includeFiltered=maybe"), 400, "INVALID_PARAMETER");
        assertThat(fields(err)).containsExactly("includeFiltered");
        assertThat(fields(assertError(cand(other, "regenerate=yes"), 400, "INVALID_PARAMETER")))
                .containsExactly("regenerate");
    }

    // ------------------------------------------------------------------ decision f: owner preferences

    @Test
    void ownerFilteredJobsAreHiddenUnscoredAndUncountedUnlessIncludeFiltered() {
        UUID owner = saveProfile();
        UUID pass = feedJob(jdbc, "Pass", "Acme", feed().verdict("PASS", "[]").flags("[\"SALARY_OTHER_CURRENCY\"]"),
                "Java", "SQL");
        UUID filtered = feedJob(jdbc, "Filtered", "Acme", feed().verdict("FILTERED", "[\"SENIORITY\",\"SALARY\"]")
                .flags("[\"IMMEDIATE_START\"]"), "Java");
        UUID unevaluated = feedJob(jdbc, "Unevaluated", "Acme", feed(), "SQL");
        UUID manual = job("Manual", "Acme", "Java");
        feedJob(jdbc, "Filtered Skill-less", "Acme", feed().verdict("FILTERED", "[\"TITLE\"]"));
        jobWithoutSkills("Manual Skill-less", "Acme");

        JsonNode page = ok(profile(null));
        assertThat(jobIds(page)).containsExactlyInAnyOrder(pass, unevaluated, manual);
        assertThat(page.get("totalElements").asLong()).isEqualTo(3);
        assertThat(page.get("recomputedJobs").asInt()).as("FILTERED is not a scoring target").isEqualTo(3);
        assertThat(page.get("unmatchableJobs").asLong()).as("FILTERED skill-less job not counted").isEqualTo(1);
        assertThat(rows(filtered, owner)).isZero();

        JsonNode p = item(page, pass);
        assertThat(p.get("preferenceVerdict").asText()).isEqualTo("PASS");
        assertThat(p.get("filterReasons")).isEmpty();
        assertThat(p.get("flags").toString()).isEqualTo("[\"SALARY_OTHER_CURRENCY\"]");
        JsonNode u = item(page, unevaluated);
        assertThat(u.get("preferenceVerdict").isNull()).as("not evaluated yet").isTrue();
        assertThat(u.get("filterReasons")).isEmpty();
        assertThat(u.get("flags")).isEmpty();
        assertNoPreferenceFields(item(page, manual));

        JsonNode all = ok(profile("includeFiltered=true"));
        assertThat(jobIds(all)).containsExactlyInAnyOrder(pass, unevaluated, manual, filtered);
        assertThat(all.get("recomputedJobs").asInt()).isEqualTo(1);
        assertThat(all.get("unmatchableJobs").asLong()).isEqualTo(2);
        JsonNode f = item(all, filtered);
        assertThat(f.get("preferenceVerdict").asText()).isEqualTo("FILTERED");
        assertThat(f.get("filterReasons").toString()).as("stored order").isEqualTo("[\"SENIORITY\",\"SALARY\"]");
        assertThat(f.get("flags").toString()).isEqualTo("[\"IMMEDIATE_START\"]");
        assertThat(rows(filtered, owner)).isOne();
        // same via the candidate path
        assertThat(jobIds(ok(cand(owner, null)))).doesNotContain(filtered);
        assertThat(ok(cand(owner, "includeFiltered=true"))).isEqualTo(ok(profile("includeFiltered=true")));
    }

    @Test
    void ownerWithOnlyFilteredMatchableJobsGetsNoMatchableJobs() {
        saveProfile();
        UUID filtered = feedJob(jdbc, "Filtered", "Acme", feed().verdict("FILTERED", "[\"REGION\"]"), "Java");
        JsonNode page = ok(profile(null));
        assertThat(page.get("matches")).isEmpty();
        assertThat(page.get("totalElements").asLong()).isZero();
        assertThat(page.get("recomputedJobs").asInt()).isZero();
        assertThat(page.get("reason").asText()).isEqualTo("NO_MATCHABLE_JOBS");
        JsonNode shown = ok(profile("includeFiltered=true"));
        assertThat(jobIds(shown)).containsExactly(filtered);
        assertThat(shown.get("reason").isNull()).isTrue();
    }

    @Test
    void aNonOwnerSeesFilteredJobsWithoutPreferenceFields() {
        saveProfile();
        UUID pass = feedJob(jdbc, "Pass", "Acme", feed().verdict("PASS", "[]").flags("[\"SALARY_OTHER_CURRENCY\"]"),
                "Java");
        UUID filtered = feedJob(jdbc, "Filtered", "Acme", feed().verdict("FILTERED", "[\"SENIORITY\"]")
                .flags("[\"IMMEDIATE_START\",\"LOCATION_UNKNOWN\"]"), "Java");
        UUID manual = job("Manual", "Acme", "SQL");
        feedJob(jdbc, "Filtered Skill-less", "Acme", feed().verdict("FILTERED", "[\"TITLE\"]"));

        JsonNode page = ok(cand(other, null));
        assertThat(page.get("owner").asBoolean()).isFalse();
        assertThat(page.get("profileVersion").isNull()).isTrue();
        assertThat(jobIds(page)).containsExactlyInAnyOrder(pass, filtered, manual);
        assertThat(page.get("recomputedJobs").asInt()).isEqualTo(3);
        assertThat(page.get("unmatchableJobs").asLong()).isEqualTo(1);
        page.get("matches").forEach(CandidateOwnerMatchesIT::assertNoPreferenceFields);
        // includeFiltered is ignored for anyone but the owner
        JsonNode include = ok(cand(other, "includeFiltered=true"));
        assertThat(include).isEqualTo(ok(cand(other, null)));
    }

    // ------------------------------------------------------------------ decision e: computed LOCATION_UNKNOWN

    @Test
    void locationUnknownIsComputedForAnyCallerWithACountryFilter() {
        UUID owner = saveProfile();
        UUID nowhere = feedJob(jdbc, "Nowhere", "Acme", feed().workplace("HYBRID").verdict("PASS", "[]")
                .flags("[\"SALARY_OTHER_CURRENCY\"]"), "Java");
        UUID onsiteNowhere = feedJob(jdbc, "Onsite Nowhere", "Acme", feed().workplace("ONSITE"), "Java");
        UUID storedFlag = feedJob(jdbc, "Stored Flag", "Acme", feed().workplace("UNKNOWN").verdict("PASS", "[]")
                .flags("[\"LOCATION_UNKNOWN\",\"IMMEDIATE_START\"]"), "Java");
        UUID remote = feedJob(jdbc, "Remote", "Acme", feed().workplace("REMOTE").verdict("PASS", "[]"), "Java");
        UUID za = feedJob(jdbc, "ZA", "Acme", feed().workplace("ONSITE").countries("ZA").verdict("PASS", "[]")
                .flags("[\"IMMEDIATE_START\"]"), "Java");
        UUID manual = job("Manual", "Acme", "Java");

        JsonNode o = ok(profile("country=ZA"));
        assertThat(jobIds(o)).containsExactlyInAnyOrder(nowhere, onsiteNowhere, storedFlag, remote, za, manual);
        assertThat(item(o, nowhere).get("flags").toString())
                .isEqualTo("[\"SALARY_OTHER_CURRENCY\",\"LOCATION_UNKNOWN\"]");
        assertThat(item(o, onsiteNowhere).get("flags").toString()).isEqualTo("[\"LOCATION_UNKNOWN\"]");
        assertThat(item(o, storedFlag).get("flags").toString()).as("no duplicate, stored order")
                .isEqualTo("[\"LOCATION_UNKNOWN\",\"IMMEDIATE_START\"]");
        assertThat(item(o, remote).get("flags")).isEmpty();
        assertThat(item(o, za).get("flags").toString()).isEqualTo("[\"IMMEDIATE_START\"]");
        assertThat(item(o, manual).get("flags")).isEmpty();

        JsonNode n = ok(cand(other, "country=ZA"));
        assertThat(item(n, nowhere).get("flags").toString()).isEqualTo("[\"LOCATION_UNKNOWN\"]");
        assertThat(item(n, onsiteNowhere).get("flags").toString()).isEqualTo("[\"LOCATION_UNKNOWN\"]");
        assertThat(item(n, storedFlag).get("flags").toString()).isEqualTo("[\"LOCATION_UNKNOWN\"]");
        for (UUID id : new UUID[] {remote, za, manual}) {
            assertThat(item(n, id).get("flags")).isEmpty();
        }
        for (UUID id : new UUID[] {nowhere, onsiteNowhere, storedFlag, remote, za, manual}) {
            assertThat(item(n, id).get("preferenceVerdict").isNull()).isTrue();
            assertThat(item(n, id).get("filterReasons")).isEmpty();
        }

        // without a country filter: owner sees only the stored flags, a non-owner none
        JsonNode plain = ok(profile(null));
        assertThat(item(plain, nowhere).get("flags").toString()).isEqualTo("[\"SALARY_OTHER_CURRENCY\"]");
        assertThat(item(plain, onsiteNowhere).get("flags")).isEmpty();
        assertThat(item(plain, storedFlag).get("flags").toString())
                .isEqualTo("[\"LOCATION_UNKNOWN\",\"IMMEDIATE_START\"]");
        ok(cand(other, null)).get("matches").forEach(m -> assertThat(m.get("flags")).isEmpty());
        // remote=exclude still flags the unknown-location non-remote jobs
        JsonNode ex = ok(cand(other, "country=ZA&remote=exclude"));
        assertThat(jobIds(ex)).doesNotContain(remote);
        assertThat(item(ex, onsiteNowhere).get("flags").toString()).isEqualTo("[\"LOCATION_UNKNOWN\"]");
        assertThat(owner).isNotNull();
    }

    // ------------------------------------------------------------------ §6: regenerate with AI disabled

    @Test
    void aiDisabledRegenerateRescoresEverythingAndIsNeverRateLimited() {
        UUID owner = saveProfile();
        job("A", "Acme", "Java");
        job("B", "Acme", "SQL");
        feedJob(jdbc, "C", "Acme", feed(), "Java");
        feedJob(jdbc, "Out", "Acme", feed().verdict("FILTERED", "[\"TITLE\"]"), "Java");
        jobWithoutSkills("Skill-less", "Acme");
        assertThat(ok(profile(null)).get("recomputedJobs").asInt()).isEqualTo(3);
        for (int i = 0; i < 3; i++) {
            JsonNode viaProfile = ok(profile("regenerate=true"));
            assertThat(viaProfile.get("recomputedJobs").asInt()).as("in-scope matchable jobs").isEqualTo(3);
            JsonNode viaCandidate = ok(cand(owner, "regenerate=true"));
            assertThat(viaCandidate.get("recomputedJobs").asInt()).isEqualTo(3);
            assertThat(ok(cand(owner, "regenerate=true&includeFiltered=true")).get("recomputedJobs").asInt())
                    .isEqualTo(4);
            assertThat(ok(cand(other, "regenerate=true")).get("recomputedJobs").asInt()).isEqualTo(4);
            viaProfile.get("matches").forEach(m -> assertThat(m.at("/explanation/reason").asText())
                    .isEqualTo("AI_DISABLED"));
        }
        assertThat(ok(profile(null)).get("recomputedJobs").asInt()).isZero();
    }

    // ------------------------------------------------------------------ §5.3, §12.5: MATCHES_BUSY messages

    private static void assertBusy(Res r, String message) {
        JsonNode err = assertError(r, 503, "MATCHES_BUSY");
        assertThat(r.header("Retry-After")).isEqualTo("2");
        assertThat(err.get("message").asText()).isEqualTo(message);
        assertThat(err.get("error").asText()).isEqualTo("Service Unavailable");
    }

    @Test
    void candidateEndpointsUseTheCandidateBusyMessageAndTheJobEndpointKeepsItsOwn() throws Exception {
        UUID owner = saveProfile();
        UUID job = job("Backend", "Acme", "Java");
        try (Connection c = rawConnection()) {
            holdCandidateLock(c, owner);
            Res viaCandidate = cand(owner, null);
            assertBusy(viaCandidate, CANDIDATE_BUSY);
            assertThat(viaCandidate.json().get("path").asText()).isEqualTo("/api/candidates/" + owner + "/matches");
            Res viaProfile = profile("regenerate=true");
            assertBusy(viaProfile, CANDIDATE_BUSY);
            assertThat(viaProfile.json().get("path").asText()).isEqualTo("/api/profile/matches");
        }
        try (Connection c = rawConnection()) {
            holdJobLock(c, job);
            assertBusy(api.get("/api/jobs/" + job + "/matches"), JOB_BUSY);
        }
        assertThat(ok(profile(null)).get("recomputedJobs").asInt()).isEqualTo(1);
    }

    // ------------------------------------------------------------------ §6: logging

    @Test
    void ownerLogsCarryIdsAndCountsOnly(CapturedOutput output) {
        UUID owner = saveProfile();
        feedJob(jdbc, "Feed", "Acme", feed().location("Secretville 9").description("FEED-SECRET-TEXT")
                .verdict("FILTERED", "[\"SENIORITY\"]").flags("[\"IMMEDIATE_START\"]"), "Java");
        int mark = output.getAll().length();
        JsonNode page = ok(profile("location=Secretville&includeFiltered=true&country=ZA&regenerate=true"));
        assertThat(page.get("totalElements").asLong()).isEqualTo(1);
        assertError(profile("location=x"), 400, "INVALID_PARAMETER");
        String logs = output.getAll().substring(mark);
        assertThat(logs).containsPattern("Candidate matches candidate=" + owner + " owner=true sort=SCORE "
                + "targets=1 recomputed=1 total=1 generated=0 latencyMs=\\d+");
        assertThat(logs).doesNotContain("Secretville").doesNotContain("FEED-SECRET-TEXT")
                .doesNotContain("PRIVATE-HEADLINE-TEXT").doesNotContain("Olive Owner")
                .doesNotContain("olive@example.com").doesNotContain("SENIORITY");
    }
}
