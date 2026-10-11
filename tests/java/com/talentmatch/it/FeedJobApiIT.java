package com.talentmatch.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.talentmatch.support.AbstractApiIT;
import com.talentmatch.support.Api.Res;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Phase 5 step 1: FEED jobs in the existing job API (spec §4.10, §9.2 item 15). FEED jobs are inserted
 * with JDBC (the feed pipeline does not exist yet); they are listed and readable with their origin, but
 * PUT/DELETE answer 409 DATA_CONFLICT, and the (title, company) key applies to MANUAL jobs only.
 */
class FeedJobApiIT extends AbstractApiIT {

    private UUID feedJob(String title, String company, String... requiredSkills) {
        UUID id = jdbc.queryForObject("INSERT INTO job (title, company, description, origin) "
                + "VALUES (?, ?, 'From the feed', 'FEED') RETURNING id", UUID.class, title, company);
        for (String s : requiredSkills) {
            jdbc.update("INSERT INTO job_skill (job_id, skill_id, required) "
                    + "SELECT ?, id, true FROM skill WHERE lower(name) = lower(?)", id, s);
        }
        return id;
    }

    private void withPosting(UUID jobId, String sourceKey, String token) {
        jdbc.update("INSERT INTO feed_job (job_id, dedup_key, primary_url) VALUES (?, ?, 'https://x.test/j')",
                jobId, "dedup:" + jobId);
        UUID source = jdbc.queryForObject("INSERT INTO feed_source (source_key, kind, board_token) "
                + "VALUES (?, 'GREENHOUSE', ?) RETURNING id", UUID.class, sourceKey, token);
        jdbc.update("INSERT INTO job_posting (source_id, external_id, job_id, url, title, company, content_hash) "
                + "VALUES (?, '1', ?, 'https://x.test/p', 'T', 'C', ?)", source, jobId, "b".repeat(64));
    }

    private static JsonNode item(JsonNode page, UUID id) {
        for (JsonNode n : page.get("content")) {
            if (n.get("id").asText().equals(id.toString())) {
                return n;
            }
        }
        throw new AssertionError("job " + id + " not in " + page);
    }

    private static List<String> origins(JsonNode page) {
        List<String> list = new ArrayList<>();
        page.get("content").forEach(n -> list.add(n.get("title").asText() + "/" + n.get("origin").asText()));
        return list;
    }

    @Test
    void feedJobsAreListedAndReadableWithTheirOrigin() {
        skills("Java", "SQL");
        UUID manual = job("Backend Engineer", "Acme", "Java");
        UUID feed = feedJob("Data Engineer", "Acme", "SQL");

        JsonNode all = api.get("/api/jobs").json();
        assertThat(all.get("totalElements").asLong()).isEqualTo(2);
        assertThat(item(all, manual).get("origin").asText()).isEqualTo("MANUAL");
        JsonNode f = item(all, feed);
        assertThat(f.get("origin").asText()).isEqualTo("FEED");
        assertThat(f.get("skillCount").asInt()).isEqualTo(1);
        assertThat(f.get("matchable").asBoolean()).isTrue();

        assertThat(origins(api.get("/api/jobs?skill=sql").json())).containsExactly("Data Engineer/FEED");

        Res detail = api.get("/api/jobs/" + feed);
        assertThat(detail.status()).isEqualTo(200);
        assertThat(detail.json().get("origin").asText()).isEqualTo("FEED");
        assertThat(detail.json().get("skills").get(0).get("name").asText()).isEqualTo("SQL");
        assertThat(api.get("/api/jobs/" + manual).json().get("origin").asText()).isEqualTo("MANUAL");
    }

    @Test
    void createAndUpdateResponsesShowManualOrigin() {
        skills("Java");
        Res created = api.post("/api/jobs", jobBody("Backend Engineer", "Acme", null, "Java"));
        assertThat(created.status()).isEqualTo(201);
        assertThat(created.json().get("origin").asText()).isEqualTo("MANUAL");
        String id = created.json().get("id").asText();
        Res updated = api.put("/api/jobs/" + id, jobBody("Backend Engineer II", "Acme", "x", "Java"));
        assertThat(updated.status()).isEqualTo(200);
        assertThat(updated.json().get("origin").asText()).isEqualTo("MANUAL");
        assertThat(api.get("/api/jobs/" + id).json()).isEqualTo(updated.json());
    }

    @Test
    void putOnAFeedJobIsAConflictAndChangesNothing() {
        skills("Java");
        UUID feed = feedJob("Data Engineer", "Acme", "Java");
        JsonNode before = api.get("/api/jobs/" + feed).json();

        JsonNode err = assertError(api.put("/api/jobs/" + feed, jobBody("Renamed", "Acme", "x", "Java")),
                409, "DATA_CONFLICT");
        assertThat(err.get("message").asText()).contains("job feed").doesNotContain("(source");
        // 409 comes before body validation (order: 404, 409, 400)
        assertError(api.put("/api/jobs/" + feed, "{\"title\":\"\"}"), 409, "DATA_CONFLICT");
        // an unknown id is still 404
        assertError(api.put("/api/jobs/" + UUID.randomUUID(), jobBody("X", "Y", null, "Java")), 404, "JOB_NOT_FOUND");

        assertThat(api.get("/api/jobs/" + feed).json()).isEqualTo(before);
    }

    @Test
    void deleteOfAFeedJobIsAConflictAndKeepsTheJob() {
        skills("Java");
        UUID feed = feedJob("Data Engineer", "Acme", "Java");
        assertError(api.delete("/api/jobs/" + feed), 409, "DATA_CONFLICT");
        assertThat(api.get("/api/jobs/" + feed).status()).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM job_skill WHERE job_id = ?", Integer.class, feed))
                .isEqualTo(1);
        assertError(api.delete("/api/jobs/" + UUID.randomUUID()), 404, "JOB_NOT_FOUND");
    }

    @Test
    void conflictMessageNamesTheSourceButNoSecrets() {
        skills("Java");
        UUID feed = feedJob("Data Engineer", "Acme", "Java");
        withPosting(feed, "greenhouse:acme", "acme");
        JsonNode put = assertError(api.put("/api/jobs/" + feed, jobBody("X", "Acme", null, "Java")),
                409, "DATA_CONFLICT");
        assertThat(put.get("message").asText()).contains("(source greenhouse:acme)");
        JsonNode del = assertError(api.delete("/api/jobs/" + feed), 409, "DATA_CONFLICT");
        assertThat(del.get("message").asText()).contains("(source greenhouse:acme)");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM feed_job", Integer.class)).isEqualTo(1);
    }

    @Test
    void manualJobMayShareTitleAndCompanyWithAFeedJob() {
        skills("Java");
        UUID feed = feedJob("Backend Engineer", "Acme", "Java");
        feedJob("Backend Engineer", "Acme", "Java");   // two feed jobs with the same key are fine too

        Res created = api.post("/api/jobs", jobBody("Backend Engineer", "Acme", null, "Java"));
        assertThat(created.status()).as("%s", created).isEqualTo(201);
        assertThat(created.json().get("origin").asText()).isEqualTo("MANUAL");
        UUID manual = UUID.fromString(created.json().get("id").asText());
        assertThat(manual).isNotEqualTo(feed);

        // ... but the MANUAL key still holds
        assertError(api.post("/api/jobs", jobBody("Backend Engineer", "Acme", null, "Java")), 409, "JOB_ALREADY_EXISTS");
        // renaming another MANUAL job onto a FEED job's key is fine; onto the MANUAL one is not
        UUID other = job("Frontend Engineer", "Acme", "Java");
        assertThat(api.put("/api/jobs/" + other, jobBody("Data Engineer", "Acme", null, "Java")).status())
                .isEqualTo(200);
        feedJob("Data Engineer", "Acme", "Java");
        assertThat(api.put("/api/jobs/" + other, jobBody("Data Engineer", "Acme", "same key", "Java")).status())
                .as("a FEED job with the same key does not block an update").isEqualTo(200);
        assertError(api.put("/api/jobs/" + other, jobBody("Backend Engineer", "Acme", null, "Java")),
                409, "JOB_ALREADY_EXISTS");

        assertThat(origins(api.get("/api/jobs").json())).containsExactlyInAnyOrder(
                "Backend Engineer/FEED", "Backend Engineer/FEED", "Backend Engineer/MANUAL",
                "Data Engineer/FEED", "Data Engineer/MANUAL");
    }

    @Test
    void matchesWorkForAFeedJob() {
        skills("Java", "SQL");
        candidate("Ada Lovelace", "ada@example.com", "Java:3", "SQL:2");
        candidate("Bob Builder", "bob@example.com", "Java:1");
        UUID feed = feedJob("Data Engineer", "Acme", "Java", "SQL");

        JsonNode page = matches(feed);
        assertThat(candidateNames(page)).containsExactly("Ada Lovelace", "Bob Builder");
        assertThat(countMatchRows(feed)).isEqualTo(2);
    }
}
