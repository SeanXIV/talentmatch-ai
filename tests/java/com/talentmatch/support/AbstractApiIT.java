package com.talentmatch.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.talentmatch.support.Api.Res;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Base class for API integration tests: full app on a random port against the shared
 * Testcontainers PostgreSQL 16, all data tables truncated before each test.
 *
 * <p>Phase 3: the Phase 2 suite runs with the AI layer off ({@code talentmatch.ai.enabled=false}),
 * so its behaviour is unchanged apart from the template {@code explanation} (AI_DISABLED). AI tests
 * extend {@link AbstractAiApiIT}, which uses a second context with a fake ChatModel.
 *
 * <p>Phase 5: the feed scheduler is off, every provider base URL points at a closed port and email
 * is not configured; feed tests extend {@code AbstractFeedIT}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "talentmatch.ai.enabled=false",
                // Phase 5: no feed scheduling and no real network; email stays unconfigured
                // (talentmatch.notify.email.host is left blank on purpose).
                "talentmatch.feed.scheduler.enabled=false",
                "talentmatch.feed.greenhouse.base-url=http://localhost:1",
                "talentmatch.feed.lever.base-url=http://localhost:1",
                "talentmatch.feed.lever.eu-base-url=http://localhost:1",
                "talentmatch.feed.ashby.base-url=http://localhost:1",
                "talentmatch.feed.adzuna.base-url=http://localhost:1"})
@Import(TestcontainersConfiguration.class)
public abstract class AbstractApiIT {

    /** Words that must never appear in an error body (stack traces, class names, SQL). */
    public static final List<String> LEAKS = List.of("Exception", "at com.", "at org.", "java.", "org.springframework",
            "org.hibernate", "SELECT ", "INSERT ", "UPDATE ", "DELETE FROM", "select ", "insert into", "SQLState",
            "jdbc", "psql", "stack");

    @LocalServerPort
    protected int port;

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected ObjectMapper mapper;

    @Autowired
    protected PostgreSQLContainer<?> postgres;

    protected Api api;

    @BeforeEach
    void resetDatabase() {
        api = new Api(port, mapper);
        // No CASCADE: every table must be listed (a missing one fails loudly instead of being silently kept).
        jdbc.execute("TRUNCATE feed_notification, job_posting, feed_job, feed_source, feed_api_usage, feed_state, "
                + "job_preferences, notification_settings, skill_alias, owner_profile, owner_profile_version, resume, "
                + "job_match, candidate_skill, job_skill, candidate, job, skill");
    }

    // ------------------------------------------------------------------ fixtures via the API

    protected UUID skill(String name) {
        return skill(name, null);
    }

    /** Creates each skill (no category); the skill table is empty at the start of every test. */
    protected void skills(String... names) {
        for (String name : names) {
            skill(name);
        }
    }

    protected UUID skill(String name, String category) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        body.put("category", category);
        Res r = api.post("/api/skills", api.json(body));
        assertThat(r.status()).as("POST /api/skills %s", r).isEqualTo(201);
        return UUID.fromString(r.json().get("id").asText());
    }

    /** Skill spec "Name" or "Name:years" (years = yearsExperience). */
    protected UUID candidate(String fullName, String email, String... skills) {
        Res r = api.post("/api/candidates", candidateBody(fullName, email, null, skills));
        assertThat(r.status()).as("POST /api/candidates %s", r).isEqualTo(201);
        return UUID.fromString(r.json().get("id").asText());
    }

    protected String candidateBody(String fullName, String email, String summary, String... skills) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (String s : skills) {
            Map<String, Object> m = new LinkedHashMap<>();
            int colon = s.lastIndexOf(':');
            if (colon > 0) {
                m.put("name", s.substring(0, colon));
                m.put("yearsExperience", Integer.parseInt(s.substring(colon + 1)));
            } else {
                m.put("name", s);
            }
            list.add(m);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("fullName", fullName);
        body.put("email", email);
        body.put("summary", summary);
        body.put("skills", list);
        return api.json(body);
    }

    /** Skill spec "Name" = required, "~Name" = nice-to-have. */
    protected UUID job(String title, String company, String... skills) {
        Res r = api.post("/api/jobs", jobBody(title, company, null, skills));
        assertThat(r.status()).as("POST /api/jobs %s", r).isEqualTo(201);
        return UUID.fromString(r.json().get("id").asText());
    }

    protected String jobBody(String title, String company, String description, String... skills) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (String s : skills) {
            Map<String, Object> m = new LinkedHashMap<>();
            boolean nice = s.startsWith("~");
            m.put("name", nice ? s.substring(1) : s);
            m.put("required", !nice);
            list.add(m);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("title", title);
        body.put("company", company);
        body.put("description", description);
        body.put("skills", list);
        return api.json(body);
    }

    /** A job with no skills, as the ETL can load (the API refuses to create one). */
    protected UUID jobWithoutSkills(String title, String company) {
        return jdbc.queryForObject("INSERT INTO job (title, company) VALUES (?, ?) RETURNING id",
                UUID.class, title, company);
    }

    protected JsonNode matches(UUID jobId, String query) {
        Res r = api.get("/api/jobs/" + jobId + "/matches" + (query == null || query.isEmpty() ? "" : "?" + query));
        assertThat(r.status()).as("GET matches %s", r).isEqualTo(200);
        return r.json();
    }

    protected JsonNode matches(UUID jobId) {
        return matches(jobId, null);
    }

    protected static List<String> candidateNames(JsonNode page) {
        List<String> names = new ArrayList<>();
        page.get("matches").forEach(m -> names.add(m.get("candidateName").asText()));
        return names;
    }

    protected static List<UUID> candidateIds(JsonNode page) {
        List<UUID> ids = new ArrayList<>();
        page.get("matches").forEach(m -> ids.add(UUID.fromString(m.get("candidateId").asText())));
        return ids;
    }

    // ------------------------------------------------------------------ database helpers

    protected Instant updatedAt(String table, UUID id) {
        Timestamp ts = jdbc.queryForObject("SELECT updated_at FROM " + table + " WHERE id = ?", Timestamp.class, id);
        return ts.toInstant();
    }

    protected int countMatchRows(UUID jobId) {
        return jdbc.queryForObject("SELECT count(*) FROM job_match WHERE job_id = ?", Integer.class, jobId);
    }

    /** A separate (non-pooled) connection; closing it releases any session-level advisory lock. */
    protected Connection rawConnection() throws SQLException {
        return DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }

    /** Takes the same advisory lock MatchJdbcRepository#lockJob uses, on the given connection. */
    protected static void holdMatchLock(Connection c, UUID jobId) throws SQLException {
        try (var ps = c.prepareStatement("SELECT pg_advisory_lock(hashtextextended(?, 0))")) {
            ps.setString(1, "job_match:" + jobId);
            ps.execute();
        }
    }

    // ------------------------------------------------------------------ error assertions

    /** Common error shape; returns the body. */
    protected static JsonNode assertError(Res r, int status, String code) {
        assertThat(r.status()).as("status of %s", r).isEqualTo(status);
        JsonNode b = r.json();
        assertThat(b).as("JSON error body of %s", r).isNotNull();
        assertThat(b.get("status").asInt()).isEqualTo(status);
        assertThat(b.get("error").asText()).isNotBlank();
        assertThat(b.get("code").asText()).as("code of %s", r).isEqualTo(code);
        assertThat(b.get("message").asText()).isNotBlank();
        assertThat(b.get("path").asText()).startsWith("/");
        assertThat(Instant.parse(b.get("timestamp").asText())).isNotNull();
        assertThat(b.get("requestId").asText()).isNotBlank().isEqualTo(r.header("X-Request-Id"));
        if (b.has("fieldErrors")) {
            assertThat(b.get("fieldErrors").size()).as("fieldErrors omitted when empty").isPositive();
            List<String> fields = new ArrayList<>();
            b.get("fieldErrors").forEach(fe -> fields.add(fe.get("field").asText()));
            assertThat(fields).as("fieldErrors sorted by field").isSorted();
        }
        assertNoLeaks(r);
        return b;
    }

    protected static void assertNoLeaks(Res r) {
        for (String leak : LEAKS) {
            assertThat(r.body()).as("error body must not contain '%s'", leak).doesNotContain(leak);
        }
    }

    protected static List<String> fields(JsonNode errorBody) {
        List<String> fields = new ArrayList<>();
        if (errorBody.has("fieldErrors")) {
            errorBody.get("fieldErrors").forEach(fe -> fields.add(fe.get("field").asText()));
        }
        return fields;
    }

    protected static Map<String, String> fieldMessages(JsonNode errorBody) {
        Map<String, String> m = new LinkedHashMap<>();
        if (errorBody.has("fieldErrors")) {
            errorBody.get("fieldErrors").forEach(fe -> m.put(fe.get("field").asText(), fe.get("message").asText()));
        }
        return m;
    }
}
