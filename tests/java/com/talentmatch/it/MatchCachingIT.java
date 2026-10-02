package com.talentmatch.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.talentmatch.support.AbstractApiIT;
import com.talentmatch.support.Api.Res;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** §8.3 Caching / staleness through the API. */
class MatchCachingIT extends AbstractApiIT {

    UUID ada;
    UUID bob;
    UUID cy;
    UUID job;

    @BeforeEach
    void data() {
        skill("Java", "Language");
        skill("SQL", "Database");
        skill("Docker", "DevOps");
        skill("Kubernetes", "DevOps");
        skill("Go", "Language");
        ada = candidate("Ada Lovelace", "ada@example.com", "Java:5", "SQL", "Docker:2");
        bob = candidate("Bob Builder", "bob@example.com", "Java:1");
        cy = candidate("Cy Young", "cy@example.com");
        job = job("Backend Engineer", "Acme", "Java", "SQL", "~Docker");
    }

    private int recomputed(String query) {
        return matches(job, query).get("recomputedCandidates").asInt();
    }

    @Test
    void firstGetComputesAllThenNothing() {
        JsonNode first = matches(job);
        assertThat(first.get("recomputedCandidates").asInt()).isEqualTo(3);
        assertThat(first.get("totalElements").asLong()).isEqualTo(3);
        assertThat(countMatchRows(job)).isEqualTo(3);
        assertThat(recomputed(null)).isZero();
        assertThat(recomputed("limit=1&page=2&minScore=0.5")).isZero();
        assertThat(countMatchRows(job)).isEqualTo(3);
    }

    @Test
    void responseContentForAFullMatch() {
        JsonNode page = matches(job);
        assertThat(page.get("jobId").asText()).isEqualTo(job.toString());
        assertThat(page.get("jobTitle").asText()).isEqualTo("Backend Engineer");
        assertThat(page.get("company").asText()).isEqualTo("Acme");
        assertThat(page.get("matchable").asBoolean()).isTrue();
        assertThat(page.get("reason").isNull()).isTrue();
        assertThat(page.get("message").isNull()).isTrue();
        assertThat(page.get("page").asInt()).isZero();
        assertThat(page.get("limit").asInt()).isEqualTo(10);
        assertThat(page.get("totalPages").asInt()).isEqualTo(1);
        assertThat(candidateNames(page)).containsExactly("Ada Lovelace", "Bob Builder", "Cy Young");

        JsonNode a = page.get("matches").get(0);
        assertThat(a.get("rank").asInt()).isEqualTo(1);
        assertThat(a.get("candidateId").asText()).isEqualTo(ada.toString());
        assertThat(a.get("score").asDouble()).isEqualTo(1.0);
        assertThat(a.get("scorePercent").asInt()).isEqualTo(100);
        assertThat(a.get("summary").asText()).isEqualTo("Matches 2 of 2 required skills; 1 of 1 nice-to-have.");
        assertThat(a.at("/breakdown/earnedPoints").asInt()).isEqualTo(25);
        assertThat(a.at("/breakdown/maxPoints").asInt()).isEqualTo(25);
        assertThat(a.at("/breakdown/matchedRequired/0/name").asText()).isEqualTo("Java");
        assertThat(a.at("/breakdown/matchedRequired/0/yearsExperience").asInt()).isEqualTo(5);
        assertThat(a.at("/breakdown/matchedRequired/1/name").asText()).isEqualTo("SQL");
        assertThat(a.at("/breakdown/matchedRequired/1/yearsExperience").isNull()).isTrue();
        assertThat(a.at("/breakdown/matchedNiceToHave/0/yearsExperience").asInt()).isEqualTo(2);
        assertThat(a.at("/breakdown/missingRequired").size()).isZero();
        assertThat(a.at("/breakdown/missingNiceToHave").size()).isZero();
        assertThat(a.has("aiExplanation")).isTrue();
        assertThat(a.get("aiExplanation").isNull()).isTrue();
        assertThat(a.get("explanationStatus").asText()).isEqualTo("UNAVAILABLE");
        Instant computedAt = Instant.parse(a.get("computedAt").asText());
        Timestamp dbComputed = jdbc.queryForObject(
                "SELECT computed_at FROM job_match WHERE job_id = ? AND candidate_id = ?", Timestamp.class, job, ada);
        assertThat(computedAt).isEqualTo(dbComputed.toInstant());

        JsonNode b = page.get("matches").get(1);
        assertThat(b.get("score").asDouble()).isEqualTo(0.4);
        assertThat(b.get("scorePercent").asInt()).isEqualTo(40);
        assertThat(b.get("summary").asText()).isEqualTo("Matches 1 of 2 required skills; missing: SQL; 0 of 1 nice-to-have.");
        assertThat(b.at("/breakdown/missingRequired/0/name").asText()).isEqualTo("SQL");
        assertThat(b.at("/breakdown/missingRequired/0/yearsExperience").isNull()).isTrue();
        assertThat(b.at("/breakdown/missingNiceToHave/0/name").asText()).isEqualTo("Docker");

        JsonNode c = page.get("matches").get(2);
        assertThat(c.get("score").asDouble()).isEqualTo(0.0);
        assertThat(c.get("scorePercent").asInt()).isZero();
        assertThat(c.get("rank").asInt()).isEqualTo(3);
        assertThat(c.at("/breakdown/earnedPoints").asInt()).isZero();
    }

    @Test
    void addingASkillToOneCandidateRecomputesExactlyOne() {
        assertThat(recomputed(null)).isEqualTo(3);
        Res put = api.put("/api/candidates/" + bob, candidateBody("Bob Builder", "bob@example.com", null, "Java:1", "SQL"));
        assertThat(put.status()).as(put.toString()).isEqualTo(200);
        JsonNode page = matches(job);
        assertThat(page.get("recomputedCandidates").asInt()).isEqualTo(1);
        assertThat(page.at("/matches/1/candidateName").asText()).isEqualTo("Bob Builder");
        assertThat(page.at("/matches/1/score").asDouble()).isEqualTo(0.8);
        assertThat(recomputed(null)).isZero();
    }

    @Test
    void changingAnIrrelevantSkillStillCountsAsAChange() {
        assertThat(recomputed(null)).isEqualTo(3);
        api.put("/api/candidates/" + cy, candidateBody("Cy Young", "cy@example.com", null, "Go"));
        assertThat(recomputed(null)).isEqualTo(1);
    }

    @Test
    void changingJobSkillsRecomputesEveryone() {
        assertThat(recomputed(null)).isEqualTo(3);
        Res put = api.put("/api/jobs/" + job, jobBody("Backend Engineer", "Acme", null, "Java", "SQL", "~Docker", "~Kubernetes"));
        assertThat(put.status()).as(put.toString()).isEqualTo(200);
        JsonNode page = matches(job);
        assertThat(page.get("recomputedCandidates").asInt()).isEqualTo(3);
        assertThat(page.at("/matches/0/score").asDouble()).isEqualTo(0.8333);
        assertThat(page.at("/matches/0/scorePercent").asInt()).isEqualTo(83);
        assertThat(recomputed(null)).isZero();

        // Toggling required on one skill also makes everyone stale.
        api.put("/api/jobs/" + job, jobBody("Backend Engineer", "Acme", null, "Java", "~SQL", "~Docker", "~Kubernetes"));
        assertThat(recomputed(null)).isEqualTo(3);

        // A scalar-only job change (description) makes everyone stale too.
        api.put("/api/jobs/" + job, jobBody("Backend Engineer", "Acme", "New text", "Java", "~SQL", "~Docker", "~Kubernetes"));
        assertThat(recomputed(null)).isEqualTo(3);
    }

    @Test
    void newCandidateIsPickedUp() {
        assertThat(recomputed(null)).isEqualTo(3);
        candidate("Dee Dee", "dee@example.com", "SQL");
        JsonNode page = matches(job);
        assertThat(page.get("recomputedCandidates").asInt()).isEqualTo(1);
        assertThat(page.get("totalElements").asLong()).isEqualTo(4);
    }

    @Test
    void regenerateRescoresEveryCandidateAndAdvancesComputedAt() {
        assertThat(recomputed(null)).isEqualTo(3);
        Timestamp before = jdbc.queryForObject("SELECT max(computed_at) FROM job_match WHERE job_id = ?",
                Timestamp.class, job);
        assertThat(recomputed("regenerate=true")).isEqualTo(3);
        Timestamp min = jdbc.queryForObject("SELECT min(computed_at) FROM job_match WHERE job_id = ?",
                Timestamp.class, job);
        assertThat(min.toInstant()).isAfter(before.toInstant());
        assertThat(recomputed("regenerate=TRUE")).isEqualTo(3);
        assertThat(recomputed("regenerate=false")).isZero();
        assertThat(countMatchRows(job)).isEqualTo(3);
    }

    @Test
    void identicalPutsWriteNothingAndKeepMatchesFresh() {
        assertThat(recomputed(null)).isEqualTo(3);
        Instant adaTs = updatedAt("candidate", ada);
        Instant jobTs = updatedAt("job", job);

        // Same values, different order / case / whitespace in skill names and email.
        Res c = api.put("/api/candidates/" + ada,
                candidateBody(" Ada Lovelace ", "ADA@example.com", null, "docker:2", " java :5", "SQL"));
        assertThat(c.status()).as(c.toString()).isEqualTo(200);
        Res j = api.put("/api/jobs/" + job, jobBody("Backend Engineer", "Acme", null, "~Docker", "sql", "Java"));
        assertThat(j.status()).as(j.toString()).isEqualTo(200);

        assertThat(updatedAt("candidate", ada)).isEqualTo(adaTs);
        assertThat(updatedAt("job", job)).isEqualTo(jobTs);
        assertThat(Instant.parse(c.json().get("updatedAt").asText())).isEqualTo(adaTs);
        assertThat(recomputed(null)).isZero();
    }

    @Test
    void deletedCandidateDisappearsWithoutRecompute() {
        assertThat(recomputed(null)).isEqualTo(3);
        assertThat(api.delete("/api/candidates/" + bob).status()).isEqualTo(204);
        JsonNode page = matches(job);
        assertThat(page.get("recomputedCandidates").asInt()).isZero();
        assertThat(page.get("totalElements").asLong()).isEqualTo(2);
        assertThat(candidateNames(page)).containsExactly("Ada Lovelace", "Cy Young");
    }

    @Test
    void matchesAreCachedPerJob() {
        UUID other = job("Data Engineer", "Acme", "SQL");
        assertThat(recomputed(null)).isEqualTo(3);
        assertThat(matches(other).get("recomputedCandidates").asInt()).isEqualTo(3);
        // editing one job doesn't touch the other's cache
        api.put("/api/jobs/" + other, jobBody("Data Engineer", "Acme", null, "SQL", "~Go"));
        assertThat(recomputed(null)).isZero();
        assertThat(matches(other).get("recomputedCandidates").asInt()).isEqualTo(3);
    }
}
