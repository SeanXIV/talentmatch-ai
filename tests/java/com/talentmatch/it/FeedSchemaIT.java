package com.talentmatch.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.talentmatch.support.AbstractApiIT;
import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;

/**
 * Phase 5 step 1: V5 constraints, checked directly with JDBC (spec §9.2 item 1, §3). The app context
 * starting at all proves Flyway applied V1–V5 and Hibernate {@code validate} passed.
 */
class FeedSchemaIT extends AbstractApiIT {

    private static final String HASH = "a".repeat(64);

    // ------------------------------------------------------------------ helpers

    private void rejected(String sql, Object... args) {
        assertThatThrownBy(() -> jdbc.update(sql, args)).as(sql).isInstanceOf(DataAccessException.class);
    }

    private UUID jobRow(String title, String company, String origin) {
        return jdbc.queryForObject("INSERT INTO job (title, company, origin) VALUES (?, ?, ?) RETURNING id",
                UUID.class, title, company, origin);
    }

    /** A FEED job plus its feed_job row (dedup key = the given key). */
    private UUID feedJob(String title, String company, String dedupKey) {
        UUID id = jobRow(title, company, "FEED");
        jdbc.update("INSERT INTO feed_job (job_id, dedup_key, primary_url) VALUES (?, ?, 'https://x.test/j')",
                id, dedupKey);
        return id;
    }

    private UUID greenhouseSource(String token) {
        return jdbc.queryForObject("INSERT INTO feed_source (source_key, kind, board_token) "
                + "VALUES (?, 'GREENHOUSE', ?) RETURNING id", UUID.class, "greenhouse:" + token, token);
    }

    private UUID posting(UUID sourceId, UUID jobId, String externalId) {
        return jdbc.queryForObject("INSERT INTO job_posting (source_id, external_id, job_id, url, title, company, "
                + "content_hash) VALUES (?, ?, ?, 'https://x.test/p', 'T', 'C', ?) RETURNING id",
                UUID.class, sourceId, externalId, jobId, HASH);
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }

    // ------------------------------------------------------------------ migration history

    @Test
    void flywayAppliedV1ToV5() {
        List<String> versions = jdbc.queryForList(
                "SELECT version FROM flyway_schema_history WHERE success AND version IS NOT NULL "
                        + "ORDER BY installed_rank", String.class);
        assertThat(versions).containsSubsequence("1", "2", "3", "4", "5");
        for (String t : List.of("skill_alias", "feed_source", "feed_job", "job_posting", "job_preferences",
                "notification_settings", "feed_notification", "feed_state", "feed_api_usage")) {
            assertThat(jdbc.queryForObject("SELECT to_regclass(?) IS NOT NULL", Boolean.class, "public." + t))
                    .as("table %s", t).isTrue();
        }
    }

    @Test
    void oldTitleCompanyConstraintIsReplacedByThePartialIndex() {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_constraint WHERE conname = 'uq_job_title_company'",
                Integer.class)).isZero();
        String def = jdbc.queryForObject("SELECT indexdef FROM pg_indexes WHERE indexname = 'uq_job_title_company_manual'",
                String.class);
        assertThat(def).contains("UNIQUE").contains("(title, company)").contains("WHERE").contains("'MANUAL'");
        assertThat(jdbc.queryForObject("SELECT contype::text FROM pg_constraint WHERE conname = 'uq_job_id_origin'",
                String.class)).isEqualTo("u");
    }

    // ------------------------------------------------------------------ job origin

    @Test
    void originDefaultsToManualAndOnlyKnownValuesAreAllowed() {
        UUID id = jdbc.queryForObject("INSERT INTO job (title, company) VALUES ('A', 'B') RETURNING id", UUID.class);
        assertThat(jdbc.queryForObject("SELECT origin FROM job WHERE id = ?", String.class, id)).isEqualTo("MANUAL");
        rejected("INSERT INTO job (title, company, origin) VALUES ('A2', 'B', 'OTHER')");
        rejected("INSERT INTO job (title, company, origin) VALUES ('A3', 'B', NULL)");
        rejected("INSERT INTO job (title, company, origin) VALUES ('A4', 'B', 'manual')");
    }

    @Test
    void titleCompanyIsUniqueAmongManualJobsOnly() {
        jobRow("Backend Engineer", "Acme", "FEED");
        jobRow("Backend Engineer", "Acme", "FEED");                // two FEED jobs: allowed
        jobRow("Backend Engineer", "Acme", "MANUAL");              // MANUAL next to FEED: allowed
        assertThatThrownBy(() -> jobRow("Backend Engineer", "Acme", "MANUAL"))
                .isInstanceOf(DuplicateKeyException.class)
                .hasMessageContaining("uq_job_title_company_manual");
        assertThat(count("job")).isEqualTo(3);
    }

    @Test
    void etlUpsertWithThePartialIndexPredicateStillWorks() {
        String upsert = """
                INSERT INTO job (title, company, description) VALUES (?, ?, ?)
                ON CONFLICT (title, company) WHERE origin = 'MANUAL' DO UPDATE
                    SET description = EXCLUDED.description
                    WHERE job.description IS DISTINCT FROM EXCLUDED.description""";
        jobRow("Data Engineer", "Acme", "FEED");
        assertThat(jdbc.update(upsert, "Data Engineer", "Acme", "v1")).isEqualTo(1);   // insert (FEED ignored)
        assertThat(jdbc.update(upsert, "Data Engineer", "Acme", "v2")).isEqualTo(1);   // update of the MANUAL row
        assertThat(jdbc.update(upsert, "Data Engineer", "Acme", "v2")).isZero();       // no-op
        assertThat(jdbc.queryForList("SELECT origin || ':' || coalesce(description, '-') FROM job ORDER BY 1",
                String.class)).containsExactly("FEED:-", "MANUAL:v2");
        // Without the predicate PostgreSQL can't infer the partial index (what the loader change avoids).
        rejected("INSERT INTO job (title, company) VALUES ('X', 'Y') ON CONFLICT (title, company) DO NOTHING");
    }

    // ------------------------------------------------------------------ feed_job

    @Test
    void feedJobCanOnlyReferenceAFeedJob() {
        UUID manual = jobRow("Manual", "Acme", "MANUAL");
        rejected("INSERT INTO feed_job (job_id, dedup_key, primary_url) VALUES (?, 'k', 'https://x.test')", manual);
        rejected("INSERT INTO feed_job (job_id, dedup_key, primary_url) VALUES (?, 'k', 'https://x.test')",
                UUID.randomUUID());
        UUID feed = jobRow("Feed", "Acme", "FEED");
        rejected("INSERT INTO feed_job (job_id, origin, dedup_key, primary_url) VALUES (?, 'MANUAL', 'k', "
                + "'https://x.test')", feed);
        jdbc.update("INSERT INTO feed_job (job_id, dedup_key, primary_url) VALUES (?, 'k', 'https://x.test')", feed);
        // origin can't be switched while a feed_job row points at it
        rejected("UPDATE job SET origin = 'MANUAL' WHERE id = ?", feed);
    }

    @Test
    void openDedupKeyIsUniqueButAClosedOneCanBeReposted() {
        UUID first = feedJob("Dev", "Acme", "acme|dev|remote");
        assertThatThrownBy(() -> feedJob("Dev", "Acme", "acme|dev|remote")).isInstanceOf(DataAccessException.class);
        jdbc.update("UPDATE feed_job SET closed_at = now() WHERE job_id = ?", first);
        feedJob("Dev", "Acme", "acme|dev|remote");
        assertThat(count("feed_job")).isEqualTo(2);
    }

    @Test
    void feedJobChecks() {
        UUID id = feedJob("Dev", "Acme", "k1");
        String where = " WHERE job_id = '" + id + "'";
        for (String set : List.of(
                "primary_url = 'javascript:alert(1)'",
                "primary_url = 'ftp://x.test'",
                "workplace = 'MOON'",
                "seniority = 'GURU'",
                "salary_min = -1",
                "salary_min = 200, salary_max = 100",
                "salary_currency = 'zar'",
                "salary_period = 'WEEK'",
                "description_hash = 'XYZ'",
                "dictionary_skills = '{}'",
                "ai_skills = '{}'",
                "ai_suggestions = 'null'",
                "filter_reasons = '{}'",
                "filter_flags = '1'",
                "enrichment_status = 'DONE'",
                "enrichment_status = 'FAILED'",                                         // no failure reason
                "enrichment_failure = 'TIMEOUT'",                                       // reason while PENDING
                "enrichment_status = 'FAILED', enrichment_failure = 'SOMETHING_NEW'",
                "enrichment_status = 'SUCCEEDED'",                                      // no ai_skills/model/time
                "enrichment_status = 'SUCCEEDED', ai_skills = '[]', enrichment_model = 'm'",
                "enrichment_attempts = -1",
                "preference_verdict = 'MAYBE'",
                "origin = 'MANUAL'")) {
            rejected("UPDATE feed_job SET " + set + where);
        }
        jdbc.update("UPDATE feed_job SET enrichment_status = 'SUCCEEDED', ai_skills = '[]', enrichment_model = 'm', "
                + "enriched_at = now(), salary_min = 100, salary_max = 100, salary_currency = 'ZAR', "
                + "salary_period = 'MONTH', description_hash = ?, preference_verdict = 'PASS'" + where, HASH);
        jdbc.update("UPDATE feed_job SET enrichment_status = 'FAILED', enrichment_failure = 'TOO_MANY_ATTEMPTS'" + where);
    }

    @Test
    void feedJobDefaults() {
        UUID id = feedJob("Dev", "Acme", "k1");
        var row = jdbc.queryForMap("SELECT origin, workplace, seniority, enrichment_status, baseline, "
                + "process_after IS NOT NULL AS due, country_codes::text AS cc FROM feed_job WHERE job_id = ?", id);
        assertThat(row).containsEntry("origin", "FEED").containsEntry("workplace", "UNKNOWN")
                .containsEntry("seniority", "UNKNOWN").containsEntry("enrichment_status", "PENDING")
                .containsEntry("baseline", false).containsEntry("due", true).containsEntry("cc", "{}");
    }

    @Test
    void deletingAFeedJobCascadesToItsFeedRows() {
        UUID source = greenhouseSource("acme");
        UUID job = feedJob("Dev", "Acme", "k1");
        posting(source, job, "1");
        jdbc.update("INSERT INTO feed_notification (job_id, channel, score) VALUES (?, 'EMAIL', 0.8)", job);
        jdbc.update("DELETE FROM job WHERE id = ?", job);
        assertThat(count("feed_job") + count("job_posting") + count("feed_notification")).isZero();
        assertThat(count("feed_source")).isEqualTo(1);
    }

    @Test
    void deletingASourceCascadesToItsPostingsOnly() {
        UUID source = greenhouseSource("acme");
        UUID job = feedJob("Dev", "Acme", "k1");
        posting(source, job, "1");
        jdbc.update("DELETE FROM feed_source WHERE id = ?", source);
        assertThat(count("job_posting")).isZero();
        assertThat(count("feed_job")).isEqualTo(1);   // orphan cleanup is the service's job (§9.2 item 2)
    }

    // ------------------------------------------------------------------ feed_source

    @Test
    void feedSourceShapeAndChecks() {
        // valid shapes
        greenhouseSource("acme");
        jdbc.update("INSERT INTO feed_source (source_key, kind, board_token, options) "
                + "VALUES ('lever:eu:acme', 'LEVER', 'acme', '{\"region\":\"eu\"}')");
        jdbc.update("INSERT INTO feed_source (source_key, kind, board_token) VALUES ('ashby:a.b-c_d', 'ASHBY', 'a.b-c_d')");
        jdbc.update("INSERT INTO feed_source (source_key, kind, managed_by, options) "
                + "VALUES ('adzuna:za:1', 'ADZUNA', 'PREFERENCES', '{\"country\":\"za\",\"what\":\"java\"}')");
        // invalid shapes
        rejected("INSERT INTO feed_source (source_key, kind) VALUES ('greenhouse:x', 'GREENHOUSE')");
        rejected("INSERT INTO feed_source (source_key, kind, board_token, managed_by) "
                + "VALUES ('greenhouse:y', 'GREENHOUSE', 'y', 'PREFERENCES')");
        rejected("INSERT INTO feed_source (source_key, kind, options) VALUES ('adzuna:2', 'ADZUNA', '{}')");
        rejected("INSERT INTO feed_source (source_key, kind, board_token, options) "
                + "VALUES ('adzuna:4', 'ADZUNA', 'tok', '{\"country\":\"za\"}')");
        rejected("INSERT INTO feed_source (source_key, kind, board_token) VALUES ('workday:z', 'WORKDAY', 'z')");
        rejected("INSERT INTO feed_source (source_key, kind, board_token) VALUES ('greenhouse:bad', 'GREENHOUSE', 'a/b')");
        rejected("INSERT INTO feed_source (source_key, kind, board_token) VALUES ('greenhouse:sp', 'GREENHOUSE', 'a b')");
        rejected("INSERT INTO feed_source (source_key, kind, board_token) VALUES ('greenhouse:acme', 'GREENHOUSE', 'acme')");
        rejected("INSERT INTO feed_source (source_key, kind, board_token, options) "
                + "VALUES ('greenhouse:arr', 'GREENHOUSE', 'arr', '[]')");

        String where = " WHERE source_key = 'greenhouse:acme'";
        for (String set : List.of("state = 'STOPPED'", "poll_interval_seconds = 59", "poll_interval_seconds = 86401",
                "last_status = 'WEIRD'", "consecutive_failures = -1", "open_postings = -1",
                "content_hash = 'ABC'", "managed_by = 'ROBOT'")) {
            rejected("UPDATE feed_source SET " + set + where);
        }
        jdbc.update("UPDATE feed_source SET state = 'PAUSED', poll_interval_seconds = 60, last_status = "
                + "'SUSPICIOUS_EMPTY', content_hash = ?" + where, HASH);
    }

    @Test
    void feedSourceUpdatedAtTriggerFires() {
        UUID id = greenhouseSource("acme");
        jdbc.update("UPDATE feed_source SET updated_at = now() - interval '1 day' WHERE id = ?", id);
        Timestamp after = jdbc.queryForObject("SELECT updated_at FROM feed_source WHERE id = ?", Timestamp.class, id);
        assertThat(after.toInstant()).isAfter(java.time.Instant.now().minusSeconds(3600));
    }

    // ------------------------------------------------------------------ job_posting

    @Test
    void jobPostingChecks() {
        UUID source = greenhouseSource("acme");
        UUID job = feedJob("Dev", "Acme", "k1");
        posting(source, job, "1");
        assertThatThrownBy(() -> posting(source, job, "1")).isInstanceOf(DuplicateKeyException.class);
        assertThatThrownBy(() -> posting(source, job, "  ")).isInstanceOf(DataAccessException.class);
        UUID manual = jobRow("Manual", "Acme", "MANUAL");
        assertThatThrownBy(() -> posting(source, manual, "2")).as("a posting needs a feed_job")
                .isInstanceOf(DataAccessException.class);
        String where = " WHERE external_id = '1'";
        for (String set : List.of("url = 'data:x'", "title = ' '", "company = ''", "workplace = 'X'",
                "salary_period = 'WEEK'", "content_hash = 'abc'")) {
            rejected("UPDATE job_posting SET " + set + where);
        }
    }

    // ------------------------------------------------------------------ singletons, notifications, usage

    @Test
    void singletonTablesHoldAtMostOneRow() {
        jdbc.update("INSERT INTO job_preferences (preferences, version) VALUES ('{}', 1)");
        rejected("INSERT INTO job_preferences (preferences, version) VALUES ('{}', 2)");
        rejected("INSERT INTO job_preferences (id, preferences, version) VALUES (false, '{}', 1)");
        rejected("UPDATE job_preferences SET preferences = '[]'");
        rejected("UPDATE job_preferences SET version = 0");

        jdbc.update("INSERT INTO notification_settings DEFAULT VALUES");
        var s = jdbc.queryForMap("SELECT enabled, channel, min_score, max_per_hour FROM notification_settings");
        assertThat(s).containsEntry("enabled", false).containsEntry("channel", "EMAIL")
                .containsEntry("min_score", 0.6).containsEntry("max_per_hour", 20);
        rejected("INSERT INTO notification_settings DEFAULT VALUES");
        for (String set : List.of("channel = 'SMS'", "min_score = 1.01", "min_score = -0.1", "max_per_hour = 0",
                "max_per_hour = 201")) {
            rejected("UPDATE notification_settings SET " + set);
        }

        jdbc.update("INSERT INTO feed_state DEFAULT VALUES");
        rejected("INSERT INTO feed_state DEFAULT VALUES");
        rejected("INSERT INTO feed_state (id) VALUES (false)");
    }

    @Test
    void feedNotificationIsUniquePerJobAndChecked() {
        UUID job = feedJob("Dev", "Acme", "k1");
        jdbc.update("INSERT INTO feed_notification (job_id, channel, score) VALUES (?, 'EMAIL', 0.8)", job);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO feed_notification (job_id, channel, score) "
                + "VALUES (?, 'EMAIL', 0.9)", job)).isInstanceOf(DuplicateKeyException.class);
        UUID other = feedJob("Dev 2", "Acme", "k2");
        rejected("INSERT INTO feed_notification (job_id, channel, score) VALUES (?, 'SMS', 0.8)", other);
        rejected("INSERT INTO feed_notification (job_id, channel, score) VALUES (?, 'EMAIL', 1.5)", other);
        rejected("INSERT INTO feed_notification (job_id, channel, score) VALUES (?, 'EMAIL', -0.1)", other);
        UUID manual = jobRow("Manual", "Acme", "MANUAL");
        rejected("INSERT INTO feed_notification (job_id, channel, score) VALUES (?, 'EMAIL', 0.8)", manual);

        String where = " WHERE job_id = '" + job + "'";
        for (String set : List.of("status = 'SENT'", "sent_at = now()", "status = 'BOUNCED'", "attempts = -1")) {
            rejected("UPDATE feed_notification SET " + set + where);
        }
        jdbc.update("UPDATE feed_notification SET status = 'SENT', sent_at = now(), attempts = 1" + where);
    }

    @Test
    void feedApiUsageIsOneRowPerProviderAndDay() {
        jdbc.update("INSERT INTO feed_api_usage (provider, day, requests) VALUES ('adzuna', DATE '2026-10-07', 1)");
        jdbc.update("INSERT INTO feed_api_usage (provider, day) VALUES ('adzuna', DATE '2026-10-08')");
        assertThatThrownBy(() -> jdbc.update("INSERT INTO feed_api_usage (provider, day) "
                + "VALUES ('adzuna', DATE '2026-10-07')")).isInstanceOf(DuplicateKeyException.class);
        rejected("UPDATE feed_api_usage SET requests = -1");
    }

    // ------------------------------------------------------------------ skill_alias

    @Test
    void skillAliasesAreCaseInsensitiveTrimmedAndCascade() {
        UUID pg = jdbc.queryForObject("INSERT INTO skill (name) VALUES ('PostgreSQL') RETURNING id", UUID.class);
        jdbc.update("INSERT INTO skill_alias (skill_id, alias) VALUES (?, 'Postgres')", pg);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO skill_alias (skill_id, alias) VALUES (?, 'POSTGRES')", pg))
                .isInstanceOf(DuplicateKeyException.class);
        rejected("INSERT INTO skill_alias (skill_id, alias) VALUES (?, ' psql')", pg);
        rejected("INSERT INTO skill_alias (skill_id, alias) VALUES (?, '')", pg);
        rejected("INSERT INTO skill_alias (skill_id, alias) VALUES (?, 'pg')", UUID.randomUUID());
        jdbc.update("DELETE FROM skill WHERE id = ?", pg);
        assertThat(count("skill_alias")).isZero();
    }
}
