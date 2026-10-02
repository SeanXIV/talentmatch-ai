package com.talentmatch.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.talentmatch.support.AbstractApiIT;
import com.talentmatch.support.Api.Res;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** §8.7 error shape / validation, §8.8 404 / 405 / 409 (+ 415). */
class ErrorHandlingIT extends AbstractApiIT {

    private static final String ID_HINT = "Ids look like 3f2c0e9a-1b2c-4d5e-8f90-a1b2c3d4e5f6.";

    UUID job;

    @BeforeEach
    void data() {
        skill("Java");
        skill("SQL");
        job = job("Backend Engineer", "Acme", "Java");
    }

    // ------------------------------------------------------------------ 400 VALIDATION_FAILED

    @Test
    void allCandidateFieldErrorsReportedAtOnceAndSorted() {
        String body = """
                {"fullName":"   ","email":"not-an-email","summary":null,
                 "skills":[{"name":"Java","yearsExperience":61},{"name":"Kubernets"},{"name":" java "}]}""";
        Res r = api.post("/api/candidates", body, "X-Request-Id", "qa-validation-1");
        JsonNode err = assertError(r, 400, "VALIDATION_FAILED");
        assertThat(err.get("error").asText()).isEqualTo("Bad Request");
        assertThat(err.get("path").asText()).isEqualTo("/api/candidates");
        assertThat(err.get("requestId").asText()).isEqualTo("qa-validation-1");
        assertThat(err.get("message").asText()).isEqualTo("5 fields are invalid. Fix them and try again.");
        assertThat(fieldMessages(err)).containsExactly(
                Map.entry("email", "'not-an-email' is not a valid email address. Use a format like ada@example.com."),
                Map.entry("fullName", "Full name is required."),
                Map.entry("skills[0].yearsExperience", "Years of experience must be between 0 and 60 (got 61)."),
                Map.entry("skills[1].name", "Unknown skill 'Kubernets'. Check the spelling or create it with POST /api/skills."),
                Map.entry("skills[2].name", "Duplicate skill 'java' (already listed at skills[0])."));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM candidate", Integer.class)).isZero();
    }

    @Test
    void singleFieldErrorMessage() {
        Res r = api.post("/api/candidates", "{\"fullName\":\"Ada\",\"email\":\"ada@example.com\",\"skills\":[{\"name\":\"Java\",\"yearsExperience\":-1}]}");
        JsonNode err = assertError(r, 400, "VALIDATION_FAILED");
        assertThat(err.get("message").asText()).isEqualTo("1 field is invalid. Fix it and try again.");
        assertThat(fields(err)).containsExactly("skills[0].yearsExperience");
        // boundaries 0 and 60 are fine
        assertThat(api.post("/api/candidates", candidateBody("Ada", "ada@example.com", null, "Java:0", "SQL:60")).status())
                .isEqualTo(201);
    }

    @Test
    void jobValidationMatchesSpecExample() {
        Res r = api.post("/api/jobs", "{\"company\":\"Acme\",\"skills\":[]}");
        JsonNode err = assertError(r, 400, "VALIDATION_FAILED");
        assertThat(err.get("message").asText()).isEqualTo("2 fields are invalid. Fix them and try again.");
        assertThat(fieldMessages(err)).containsExactly(
                Map.entry("skills", "Add at least one skill so candidates can be ranked for this job."),
                Map.entry("title", "Title is required."));
    }

    @Test
    void lengthLimits() {
        Res r = api.post("/api/jobs", jobBody("t".repeat(301), "c".repeat(201), "d".repeat(20_001), "Java", "Nope", "~SQL", "sql"));
        JsonNode err = assertError(r, 400, "VALIDATION_FAILED");
        assertThat(fields(err)).containsExactly("company", "description", "skills[1].name", "skills[3].name", "title");
        assertThat(fieldMessages(err).get("title")).isEqualTo("Title must be at most 300 characters (got 301).");

        Res s = api.post("/api/skills", "{\"name\":\"" + "x".repeat(101) + "\",\"category\":\"" + "y".repeat(101) + "\"}");
        assertThat(fields(assertError(s, 400, "VALIDATION_FAILED"))).containsExactly("category", "name");
        assertError(api.post("/api/skills", "{\"name\":\"  \"}"), 400, "VALIDATION_FAILED");
    }

    // ------------------------------------------------------------------ 400 MALFORMED_REQUEST

    @Test
    void unknownJsonFieldsAreRejected() {
        JsonNode top = assertError(api.post("/api/candidates", "{\"fullname\":\"Ada\",\"email\":\"ada@example.com\"}"),
                400, "MALFORMED_REQUEST");
        assertThat(top.get("message").asText()).isEqualTo("Unknown field 'fullname'.");
        assertThat(fields(top)).containsExactly("fullname");

        JsonNode nested = assertError(api.post("/api/candidates",
                "{\"fullName\":\"Ada\",\"email\":\"ada@example.com\",\"skills\":[{\"name\":\"Java\",\"years\":5}]}"),
                400, "MALFORMED_REQUEST");
        assertThat(nested.get("message").asText()).isEqualTo("Unknown field 'skills[0].years'.");

        JsonNode skill = assertError(api.post("/api/skills", "{\"name\":\"Go\",\"type\":\"Language\"}"), 400, "MALFORMED_REQUEST");
        assertThat(skill.get("message").asText()).isEqualTo("Unknown field 'type'.");
    }

    @Test
    void malformedBodies() {
        assertThat(assertError(api.post("/api/candidates", "{\"fullName\": \"Ada\","), 400, "MALFORMED_REQUEST")
                .get("message").asText()).isEqualTo("The request body is not valid JSON.");
        assertThat(assertError(api.post("/api/candidates", "{not json}"), 400, "MALFORMED_REQUEST")
                .get("message").asText()).isEqualTo("The request body is not valid JSON.");
        JsonNode wrongType = assertError(api.post("/api/candidates",
                "{\"fullName\":\"Ada\",\"email\":\"ada@example.com\",\"skills\":[{\"name\":\"Java\",\"yearsExperience\":\"five\"}]}"),
                400, "MALFORMED_REQUEST");
        assertThat(wrongType.get("message").asText()).isEqualTo("Field 'skills[0].yearsExperience' has the wrong type.");
        assertThat(wrongType.at("/fieldErrors/0/message").asText()).isEqualTo("Expected a whole number.");
        JsonNode notList = assertError(api.post("/api/jobs", "{\"title\":\"T\",\"company\":\"C\",\"skills\":\"Java\"}"),
                400, "MALFORMED_REQUEST");
        assertThat(notList.get("message").asText()).isEqualTo("Field 'skills' has the wrong type.");
        assertThat(assertError(api.post("/api/candidates", "[1,2]"), 400, "MALFORMED_REQUEST").get("message").asText())
                .isEqualTo("The request body has the wrong shape. Send a JSON object.");
        assertThat(assertError(api.send("POST", "/api/candidates", null, "application/json"), 400, "MALFORMED_REQUEST")
                .get("message").asText()).isEqualTo("The request body is missing. Send a JSON object.");
        JsonNode boolType = assertError(api.post("/api/jobs",
                "{\"title\":\"T\",\"company\":\"C\",\"skills\":[{\"name\":\"Java\",\"required\":\"maybe\"}]}"),
                400, "MALFORMED_REQUEST");
        assertThat(boolType.get("message").asText()).isEqualTo("Field 'skills[0].required' has the wrong type.");
    }

    @Test
    void unsupportedMediaType() {
        Res r = api.send("POST", "/api/skills", "name=Go", "text/plain");
        JsonNode err = assertError(r, 415, "UNSUPPORTED_MEDIA_TYPE");
        assertThat(err.get("message").asText()).contains("Content-Type: application/json");
    }

    // ------------------------------------------------------------------ 400 INVALID_PARAMETER

    @Test
    void matchQueryParameterValidation() {
        String base = "/api/jobs/" + job + "/matches?";
        for (String q : List.of("limit=0", "limit=101", "limit=-5")) {
            JsonNode err = assertError(api.get(base + q), 400, "INVALID_PARAMETER");
            assertThat(err.get("message").asText()).isEqualTo("limit must be between 1 and 100.");
            assertThat(fields(err)).containsExactly("limit");
        }
        assertThat(assertError(api.get(base + "limit=abc"), 400, "INVALID_PARAMETER").get("message").asText())
                .isEqualTo("Parameter 'limit' must be a whole number.");
        JsonNode regen = assertError(api.get(base + "regenerate=yes"), 400, "INVALID_PARAMETER");
        assertThat(regen.get("message").asText()).isEqualTo("Parameter 'regenerate' must be true or false.");
        assertThat(fields(regen)).containsExactly("regenerate");
        for (String q : List.of("regenerate=1", "regenerate=on", "regenerate=no")) {
            assertError(api.get(base + q), 400, "INVALID_PARAMETER");
        }
        for (String q : List.of("minScore=1.5", "minScore=-0.1", "minScore=NaN")) {
            JsonNode err = assertError(api.get(base + q), 400, "INVALID_PARAMETER");
            assertThat(err.get("message").asText()).as(q).isEqualTo("minScore must be between 0 and 1.");
            assertThat(fields(err)).containsExactly("minScore");
        }
        assertThat(assertError(api.get(base + "minScore=abc"), 400, "INVALID_PARAMETER").get("message").asText())
                .isEqualTo("Parameter 'minScore' must be a number.");
        JsonNode page = assertError(api.get(base + "page=-1"), 400, "INVALID_PARAMETER");
        assertThat(page.get("message").asText()).isEqualTo("page must be 0 or greater.");
        assertThat(fields(page)).containsExactly("page");
        // valid edge values
        assertThat(api.get(base + "limit=1&minScore=1&page=0&regenerate=false").status()).isEqualTo(200);
        assertThat(api.get(base + "limit=100&minScore=0").status()).isEqualTo(200);
    }

    @Test
    void listPagingParameterValidation() {
        for (String path : List.of("/api/candidates", "/api/jobs", "/api/skills")) {
            for (String q : List.of("size=0", "size=101")) {
                JsonNode err = assertError(api.get(path + "?" + q), 400, "INVALID_PARAMETER");
                assertThat(err.get("message").asText()).as(path + "?" + q).isEqualTo("size must be between 1 and 100.");
                assertThat(fields(err)).containsExactly("size");
                assertThat(err.get("path").asText()).isEqualTo(path);
            }
            JsonNode page = assertError(api.get(path + "?page=-1"), 400, "INVALID_PARAMETER");
            assertThat(page.get("message").asText()).isEqualTo("page must be 0 or greater.");
            JsonNode both = assertError(api.get(path + "?page=-1&size=0"), 400, "INVALID_PARAMETER");
            assertThat(fields(both)).containsExactly("page", "size");
            assertError(api.get(path + "?size=abc"), 400, "INVALID_PARAMETER");
            assertThat(api.get(path + "?size=1&page=0").status()).isEqualTo(200);
            assertThat(api.get(path + "?size=100&page=1000000").status()).isEqualTo(200);
        }
        JsonNode overflow = assertError(api.get("/api/jobs?size=100&page=2147483647"), 400, "INVALID_PARAMETER");
        assertThat(fields(overflow)).containsExactly("page");
    }

    @Test
    void recomputeOnlyStaleMustBeBoolean() {
        JsonNode err = assertError(api.post("/api/matches/recompute?onlyStale=yes", null), 400, "INVALID_PARAMETER");
        assertThat(err.get("message").asText()).isEqualTo("Parameter 'onlyStale' must be true or false.");
    }

    // ------------------------------------------------------------------ 400 INVALID_ID

    @Test
    void nonUuidPathIdsAreInvalidId() {
        for (String path : List.of("/api/candidates/abc", "/api/jobs/abc", "/api/skills/abc", "/api/jobs/abc/matches",
                "/api/matches/recompute/abc")) {
            JsonNode err = assertError(api.get(path), 400, "INVALID_ID");
            assertThat(err.get("message").asText()).as(path).isEqualTo("'abc' is not a valid id. " + ID_HINT);
            assertThat(err.has("fieldErrors")).isFalse();
        }
        assertError(api.put("/api/candidates/123", candidateBody("A", "a@example.com", null)), 400, "INVALID_ID");
        assertError(api.delete("/api/jobs/not-a-uuid"), 400, "INVALID_ID");
        // long values are truncated in the echo
        String longId = "x".repeat(200);
        JsonNode err = assertError(api.get("/api/jobs/" + longId), 400, "INVALID_ID");
        assertThat(err.get("message").asText()).hasSizeLessThan(200);
    }

    // ------------------------------------------------------------------ 404 / 405

    @Test
    void notFounds() {
        UUID missing = UUID.randomUUID();
        assertThat(assertError(api.get("/api/candidates/" + missing), 404, "CANDIDATE_NOT_FOUND").get("message").asText())
                .isEqualTo("No candidate with id " + missing + ".");
        assertThat(assertError(api.get("/api/jobs/" + missing), 404, "JOB_NOT_FOUND").get("message").asText())
                .isEqualTo("No job with id " + missing + ".");
        assertThat(assertError(api.get("/api/skills/" + missing), 404, "SKILL_NOT_FOUND").get("message").asText())
                .isEqualTo("No skill with id " + missing + ".");
        assertError(api.get("/api/jobs/" + missing + "/matches"), 404, "JOB_NOT_FOUND");
        assertThat(assertError(api.get("/api/matches/recompute/" + missing), 404, "RECOMPUTE_RUN_NOT_FOUND")
                .get("message").asText()).isEqualTo("No recompute run with id " + missing
                + ". Run status is kept in memory (last 20 runs) and is lost when the server restarts.");
        assertError(api.put("/api/candidates/" + missing, candidateBody("A", "a@example.com", null)), 404, "CANDIDATE_NOT_FOUND");
        assertError(api.put("/api/jobs/" + missing, jobBody("T", "C", null, "Java")), 404, "JOB_NOT_FOUND");
        assertError(api.delete("/api/candidates/" + missing), 404, "CANDIDATE_NOT_FOUND");
        assertError(api.delete("/api/jobs/" + missing), 404, "JOB_NOT_FOUND");

        JsonNode endpoint = assertError(api.get("/api/foo"), 404, "ENDPOINT_NOT_FOUND");
        assertThat(endpoint.get("message").asText()).isEqualTo("No endpoint GET /api/foo.");
        assertError(api.post("/api/nothing/here", "{}"), 404, "ENDPOINT_NOT_FOUND");
        assertError(api.get("/nope"), 404, "ENDPOINT_NOT_FOUND");
    }

    @Test
    void methodNotAllowed() {
        UUID skillId = skill("Docker");
        Res del = api.delete("/api/skills/" + skillId);
        JsonNode err = assertError(del, 405, "METHOD_NOT_ALLOWED");
        assertThat(del.header("Allow")).contains("GET");
        assertThat(err.get("message").asText()).startsWith("Method DELETE is not supported for /api/skills/" + skillId + ".");

        Res post = api.post("/api/jobs/" + job, "{}");
        assertError(post, 405, "METHOD_NOT_ALLOWED");
        assertThat(post.header("Allow")).contains("GET").contains("PUT").contains("DELETE");

        assertError(api.get("/api/matches/recompute"), 405, "METHOD_NOT_ALLOWED");
        assertError(api.delete("/api/jobs/" + job + "/matches"), 405, "METHOD_NOT_ALLOWED");
    }

    // ------------------------------------------------------------------ 409

    @Test
    void duplicateEmailIsCaseInsensitive() {
        UUID ada = candidate("Ada Lovelace", "Ada@Example.com");
        JsonNode err = assertError(api.post("/api/candidates", candidateBody("Ada Two", "  ADA@example.COM ", null)),
                409, "EMAIL_ALREADY_EXISTS");
        assertThat(err.get("error").asText()).isEqualTo("Conflict");
        assertThat(err.get("message").asText())
                .isEqualTo("A candidate with email ada@example.com already exists (id " + ada + ").");
        UUID bob = candidate("Bob", "bob@example.com");
        assertError(api.put("/api/candidates/" + bob, candidateBody("Bob", "ada@EXAMPLE.com", null)), 409, "EMAIL_ALREADY_EXISTS");
        // keeping your own email is fine
        assertThat(api.put("/api/candidates/" + ada, candidateBody("Ada L.", "ada@example.com", null)).status()).isEqualTo(200);
    }

    @Test
    void duplicateTitleAndCompanyAfterTrimming() {
        JsonNode err = assertError(api.post("/api/jobs", jobBody("  Backend Engineer ", "Acme  ", null, "SQL")),
                409, "JOB_ALREADY_EXISTS");
        assertThat(err.get("message").asText()).contains(job.toString());
        // different case is a different job (V1 unique constraint is case-sensitive)
        assertThat(api.post("/api/jobs", jobBody("backend engineer", "Acme", null, "SQL")).status()).isEqualTo(201);
        UUID other = job("Frontend Engineer", "Acme", "SQL");
        assertError(api.put("/api/jobs/" + other, jobBody("Backend Engineer", "Acme", null, "SQL")), 409, "JOB_ALREADY_EXISTS");
    }

    @Test
    void duplicateSkillInDifferentCase() {
        UUID k8s = skill("Kubernetes", "DevOps");
        JsonNode err = assertError(api.post("/api/skills", "{\"name\":\"  kubernetes \"}"), 409, "SKILL_ALREADY_EXISTS");
        assertThat(err.get("message").asText())
                .isEqualTo("A skill named 'kubernetes' already exists as 'Kubernetes' (id " + k8s + ").");
    }

    @Test
    void concurrentDuplicateCreatesNeverReturn500() throws Exception {
        int n = 8;
        var pool = java.util.concurrent.Executors.newFixedThreadPool(n);
        try {
            var start = new java.util.concurrent.CountDownLatch(1);
            List<java.util.concurrent.Future<Res>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < n; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return api.post("/api/candidates", candidateBody("Race", "race@example.com", null, "Java"));
                }));
            }
            start.countDown();
            int created = 0;
            for (var f : futures) {
                Res r = f.get();
                if (r.status() == 201) {
                    created++;
                } else {
                    assertError(r, 409, "EMAIL_ALREADY_EXISTS");
                }
            }
            assertThat(created).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }
}
