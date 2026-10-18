package com.talentmatch.support;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Phase 6 fixtures: FEED jobs written straight to {@code job} + {@code job_skill} + {@code feed_job} (no
 * feed processor), and advisory-lock helpers for the candidate side.
 */
public final class SeekerFixtures {

    private SeekerFixtures() {
    }

    /** A feed job's {@code feed_job} facts. Build with {@link #feed()} and the withers. */
    public static final class Feed {
        Instant postedAt;
        Instant firstSeenAt = Instant.now();
        boolean baseline;
        Instant closedAt;
        String workplace = "UNKNOWN";
        String location;
        List<String> countries = List.of();
        String verdict;
        String description = "From the feed";
        String filterReasons = "[]";

        public Feed posted(Instant v) {
            postedAt = v;
            return this;
        }

        public Feed firstSeen(Instant v) {
            firstSeenAt = v;
            return this;
        }

        public Feed baseline() {
            baseline = true;
            return this;
        }

        public Feed closed(Instant v) {
            closedAt = v;
            return this;
        }

        public Feed workplace(String v) {
            workplace = v;
            return this;
        }

        public Feed location(String v) {
            location = v;
            return this;
        }

        public Feed countries(String... v) {
            countries = List.of(v);
            return this;
        }

        public Feed verdict(String v, String reasonsJson) {
            verdict = v;
            filterReasons = reasonsJson;
            return this;
        }

        public Feed description(String v) {
            description = v;
            return this;
        }
    }

    public static Feed feed() {
        return new Feed();
    }

    /**
     * Inserts a FEED job with the given skills ("Name" required, "~Name" nice-to-have; the skills must
     * exist) and its feed_job row.
     */
    public static UUID feedJob(JdbcTemplate jdbc, String title, String company, Feed f, String... skills) {
        UUID id = jdbc.queryForObject("INSERT INTO job (title, company, description, origin) VALUES (?, ?, ?, 'FEED') "
                + "RETURNING id", UUID.class, title, company, f.description);
        for (String s : skills) {
            boolean nice = s.startsWith("~");
            int n = jdbc.update("INSERT INTO job_skill (job_id, skill_id, required) SELECT ?, id, ? FROM skill "
                    + "WHERE lower(name) = lower(?)", id, !nice, nice ? s.substring(1) : s);
            if (n != 1) {
                throw new IllegalStateException("unknown skill " + s);
            }
        }
        jdbc.update("INSERT INTO feed_job (job_id, dedup_key, primary_url, first_seen_at, posted_at, closed_at, baseline, "
                        + "location_text, country_codes, workplace, preference_verdict, filter_reasons, process_after) "
                        + "VALUES (?, ?, 'https://jobs.example/' || ?, ?, ?, ?, ?, ?, CAST(? AS varchar(2)[]), ?, ?, "
                        + "CAST(? AS jsonb), NULL)",
                id, "dedup:" + id, id.toString(), ts(f.firstSeenAt), ts(f.postedAt), ts(f.closedAt), f.baseline,
                f.location, "{" + String.join(",", f.countries) + "}", f.workplace, f.verdict, f.filterReasons);
        return id;
    }

    /** Sets a MANUAL job's {@code created_at} (its listedAt); bumps updated_at too (trigger). */
    public static void setCreatedAt(JdbcTemplate jdbc, UUID jobId, Instant createdAt) {
        jdbc.update("UPDATE job SET created_at = ? WHERE id = ?", ts(createdAt), jobId);
    }

    public static Timestamp ts(Instant i) {
        return i == null ? null : Timestamp.from(i);
    }

    /** Session-level advisory lock with the same key MatchJdbcRepository#lockCandidate uses. */
    public static void holdCandidateLock(Connection c, UUID candidateId) throws SQLException {
        hold(c, "candidate_match:" + candidateId);
    }

    /** Session-level advisory lock with the same key MatchJdbcRepository#lockJob uses. */
    public static void holdJobLock(Connection c, UUID jobId) throws SQLException {
        hold(c, "job_match:" + jobId);
    }

    private static void hold(Connection c, String key) throws SQLException {
        try (var ps = c.prepareStatement("SELECT pg_advisory_lock(hashtextextended(?, 0))")) {
            ps.setString(1, key);
            ps.execute();
        }
    }

    /** Job ids in the order the page lists them. */
    public static List<UUID> jobIds(com.fasterxml.jackson.databind.JsonNode page) {
        List<UUID> ids = new ArrayList<>();
        page.get("matches").forEach(m -> ids.add(UUID.fromString(m.get("jobId").asText())));
        return ids;
    }

    public static List<String> titles(com.fasterxml.jackson.databind.JsonNode page) {
        List<String> out = new ArrayList<>();
        page.get("matches").forEach(m -> out.add(m.get("title").asText()));
        return out;
    }
}
