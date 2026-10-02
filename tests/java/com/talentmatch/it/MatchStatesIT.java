package com.talentmatch.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.talentmatch.support.AbstractApiIT;
import com.talentmatch.support.Api.Res;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** §8.5 No-skills job and §8.6 no candidates. */
class MatchStatesIT extends AbstractApiIT {

    private static final String NO_SKILLS_MESSAGE =
            "This job has no skills listed yet, so candidates can't be ranked. Add skills to see matches.";

    @Test
    void jobWithoutSkillsIsNotMatchableAndNothingIsWritten() {
        skill("Java");
        candidate("Ada Lovelace", "ada@example.com", "Java");
        candidate("Bob Builder", "bob@example.com");
        UUID job = jobWithoutSkills("ETL Job", "Loaded Corp");

        for (String q : new String[] {null, "regenerate=true", "limit=5&page=3&minScore=0.5"}) {
            JsonNode page = matches(job, q);
            assertThat(page.get("jobId").asText()).isEqualTo(job.toString());
            assertThat(page.get("jobTitle").asText()).isEqualTo("ETL Job");
            assertThat(page.get("company").asText()).isEqualTo("Loaded Corp");
            assertThat(page.get("matchable").asBoolean()).isFalse();
            assertThat(page.get("reason").asText()).isEqualTo("JOB_HAS_NO_SKILLS");
            assertThat(page.get("message").asText()).isEqualTo(NO_SKILLS_MESSAGE);
            assertThat(page.get("matches").isArray()).isTrue();
            assertThat(page.get("matches").size()).isZero();
            assertThat(page.get("totalElements").asLong()).isZero();
            assertThat(page.get("totalPages").asInt()).isZero();
            assertThat(page.get("recomputedCandidates").asInt()).isZero();
        }
        assertThat(countMatchRows(job)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM job_match", Integer.class)).isZero();

        Res detail = api.get("/api/jobs/" + job);
        assertThat(detail.json().get("matchable").asBoolean()).isFalse();
        assertThat(detail.json().get("skills").size()).isZero();
        JsonNode list = api.get("/api/jobs").json();
        assertThat(list.at("/content/0/skillCount").asInt()).isZero();
        assertThat(list.at("/content/0/matchable").asBoolean()).isFalse();
    }

    @Test
    void jobThatLosesAllSkillsBecomesNotMatchable() {
        skill("Java");
        candidate("Ada Lovelace", "ada@example.com", "Java");
        UUID job = job("Backend Engineer", "Acme", "Java");
        assertThat(matches(job).get("totalElements").asLong()).isEqualTo(1);
        jdbc.update("DELETE FROM job_skill WHERE job_id = ?", job);
        JsonNode page = matches(job);
        assertThat(page.get("matchable").asBoolean()).isFalse();
        assertThat(page.get("reason").asText()).isEqualTo("JOB_HAS_NO_SKILLS");
        assertThat(page.get("matches").size()).isZero();
    }

    @Test
    void creatingAJobWithoutSkillsIsRejected() {
        String message = "Add at least one skill so candidates can be ranked for this job.";
        for (String body : new String[] {
                "{\"title\":\"Backend Engineer\",\"company\":\"Acme\",\"skills\":[]}",
                "{\"title\":\"Backend Engineer\",\"company\":\"Acme\"}",
                "{\"title\":\"Backend Engineer\",\"company\":\"Acme\",\"skills\":null}"}) {
            Res r = api.post("/api/jobs", body);
            JsonNode err = assertError(r, 400, "VALIDATION_FAILED");
            assertThat(err.get("message").asText()).isEqualTo("1 field is invalid. Fix it and try again.");
            assertThat(err.at("/fieldErrors/0/field").asText()).isEqualTo("skills");
            assertThat(err.at("/fieldErrors/0/message").asText()).isEqualTo(message);
        }
        // PUT to zero skills is rejected too
        skill("Java");
        UUID job = job("Backend Engineer", "Acme", "Java");
        Res put = api.put("/api/jobs/" + job, "{\"title\":\"Backend Engineer\",\"company\":\"Acme\",\"skills\":[]}");
        assertThat(assertError(put, 400, "VALIDATION_FAILED").at("/fieldErrors/0/field").asText()).isEqualTo("skills");
    }

    @Test
    void noCandidatesAtAll() {
        skill("Java");
        UUID job = job("Backend Engineer", "Acme", "Java");
        for (String q : new String[] {null, "regenerate=true", "minScore=0.5"}) {
            JsonNode page = matches(job, q);
            assertThat(page.get("matchable").asBoolean()).isTrue();
            assertThat(page.get("reason").asText()).isEqualTo("NO_CANDIDATES");
            assertThat(page.get("message").asText()).isEqualTo("There are no candidates yet. Add candidates to see matches.");
            assertThat(page.get("matches").size()).isZero();
            assertThat(page.get("totalElements").asLong()).isZero();
            assertThat(page.get("totalPages").asInt()).isZero();
            assertThat(page.get("recomputedCandidates").asInt()).isZero();
        }
    }
}
