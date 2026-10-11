package com.talentmatch.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.talentmatch.feed.FeedProcessor;
import com.talentmatch.feed.FeedRefreshService;
import com.talentmatch.support.Api.Res;
import com.talentmatch.support.RouteStubServer.Reply;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

/**
 * Base for the step-7 processing ITs: {@link AbstractFeedIT} plus a stub EMAIL notifier (configured
 * unless a test switches it off), the processor, and fixtures for the owner profile, preferences,
 * notification settings and Lever postings with any location.
 */
@Import(StubNotifier.Config.class)
public abstract class AbstractFeedProcessingIT extends AbstractFeedIT {

    @Autowired
    protected FeedProcessor processor;
    @Autowired
    protected FeedRefreshService refresh;

    /** Fixed per test. */
    protected final Instant start = Instant.now().truncatedTo(ChronoUnit.SECONDS);

    @BeforeEach
    void notifierConfigured() {
        StubNotifier.CONFIGURED.set(true);
    }

    // ------------------------------------------------------------------ owner side

    /** PUT /api/profile with these (existing) skills. */
    protected Res putProfile(String... skills) {
        ObjectNode body = mapper.createObjectNode();
        body.put("createMissingSkills", false);
        ObjectNode p = body.putObject("profile");
        p.put("fullName", "Ada Lovelace");
        p.put("email", "ada@example.com");
        p.put("headline", "Backend Engineer");
        ArrayNode list = p.putArray("skills");
        for (String s : skills) {
            list.addObject().put("name", s);
        }
        return api.put("/api/profile", body.toString());
    }

    protected void profile(String... skills) {
        Res r = putProfile(skills);
        assertThat(r.status()).as("PUT /api/profile %s", r).isBetween(200, 201);
    }

    protected UUID ownerId() {
        return jdbc.queryForObject("SELECT candidate_id FROM owner_profile", UUID.class);
    }

    protected Res putPreferences(String preferencesJson) {
        return api.put("/api/preferences", "{\"preferences\": " + preferencesJson + "}");
    }

    protected void preferences(String preferencesJson) {
        Res r = putPreferences(preferencesJson);
        assertThat(r.status()).as("PUT /api/preferences %s", r).isEqualTo(200);
    }

    /** Notification settings, written with JDBC (the endpoint arrives in step 8). */
    protected void notifications(boolean enabled, double minScore) {
        jdbc.update("INSERT INTO notification_settings (id, enabled, min_score) VALUES (true, ?, ?) "
                + "ON CONFLICT (id) DO UPDATE SET enabled = EXCLUDED.enabled, min_score = EXCLUDED.min_score",
                enabled, minScore);
    }

    // ------------------------------------------------------------------ Lever postings with any location

    /** A Lever posting; {@code workplace} "remote" / "onsite" / "hybrid"; {@code country} ISO-2 or null. */
    public record Post(String id, String title, String description, Instant createdAt, String workplace,
                       String location, String country) {

        public Post at(String w, String loc, String c) {
            return new Post(id, title, description, createdAt, w, loc, c);
        }

        public Post withDescription(String d) {
            return new Post(id, title, d, createdAt, workplace, location, country);
        }

        Map<String, Object> json() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", id);
            m.put("text", title);
            m.put("hostedUrl", "https://jobs.lever.co/acme/" + id);
            Map<String, Object> categories = new LinkedHashMap<>();
            categories.put("location", location);
            categories.put("commitment", "Full-time");
            m.put("categories", categories);
            m.put("country", country);
            m.put("workplaceType", workplace);
            m.put("createdAt", createdAt == null ? null : createdAt.toEpochMilli());
            m.put("descriptionPlain", description);
            return m;
        }
    }

    /** Remote, Cape Town, ZA, posted 10 days ago (baseline on a first poll). */
    protected Post old(String id, String title, String description) {
        return new Post(id, title, description, start.minus(Duration.ofDays(10)), "remote", "Cape Town", "ZA");
    }

    /** On-site in Cape Town, ZA, posted an hour ago. */
    protected Post fresh(String id, String title, String description) {
        return new Post(id, title, description, start.minus(Duration.ofHours(1)), "onsite", "Cape Town", "ZA");
    }

    protected void serve(String token, List<Post> posts) {
        List<Map<String, Object>> list = new ArrayList<>();
        posts.forEach(p -> list.add(p.json()));
        STUB.route(leverPath(token), Reply.json(200, api.json(list)));
    }

    protected void serve(String token, Post... posts) {
        serve(token, List.of(posts));
    }

    // ------------------------------------------------------------------ assertions on the result

    protected Map<String, Boolean> jobSkills(UUID jobId) {
        Map<String, Boolean> m = new LinkedHashMap<>();
        jdbc.query("SELECT s.name, js.required FROM job_skill js JOIN skill s ON s.id = js.skill_id "
                + "WHERE js.job_id = ? ORDER BY s.name", rs -> {
                    m.put(rs.getString(1), rs.getBoolean(2));
                }, jobId);
        return m;
    }

    protected Double ownerScore(UUID jobId) {
        List<Double> s = jdbc.queryForList("SELECT m.score FROM job_match m JOIN owner_profile o "
                + "ON o.candidate_id = m.candidate_id WHERE m.job_id = ?", Double.class, jobId);
        return s.isEmpty() ? null : s.get(0);
    }

    protected int notificationCount() {
        return countRows("feed_notification");
    }

    protected int notificationCount(UUID jobId) {
        return jdbc.queryForObject("SELECT count(*) FROM feed_notification WHERE job_id = ?", Integer.class, jobId);
    }

    protected List<String> jsonStrings(Object jsonb) {
        if (jsonb == null) {
            return null;
        }
        try {
            List<String> out = new ArrayList<>();
            mapper.readTree(jsonb.toString()).forEach(n -> out.add(n.asText()));
            return out;
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    protected Integer feedState(String column) {
        List<Integer> v = jdbc.queryForList("SELECT " + column + " FROM feed_state", Integer.class);
        return v.isEmpty() ? null : v.get(0);
    }

    protected int pendingProcessing() {
        return jdbc.queryForObject("SELECT count(*) FROM feed_job WHERE process_after IS NOT NULL", Integer.class);
    }
}
