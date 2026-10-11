package com.talentmatch.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.talentmatch.support.AbstractFeedProcessingIT;
import com.talentmatch.support.StubNotifier;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * §9.2 items 3, 4 and part of 14 for step 7: dictionary skills → job_skill → preference filter →
 * owner score → PENDING feed_notification row (no sending yet). The scheduler is off, so the test
 * calls {@code processDue()}.
 */
@ExtendWith(OutputCaptureExtension.class)
class FeedProcessingIT extends AbstractFeedProcessingIT {

    private static final String BACKEND_DESC =
            "You will build Java services and write SQL every day. Kubernetes is advantageous.";
    private static final String JAVA_SQL = "We use Java and SQL.";

    private static final String PREFS = """
            {"targetTitles": ["Backend Engineer"], "excludedTitleKeywords": ["Sales"],
             "regions": {"countries": ["ZA"], "includeRemote": true, "remoteScope": "ELIGIBLE_FROM_COUNTRIES"}}""";

    /** Creates the source, polls once with one old posting (the baseline), and processes it. */
    private UUID baselined(String token) {
        UUID s = lever(token, "Acme");
        serve(token, old("base", "Platform Engineer", JAVA_SQL));
        pollOk(s);
        processor.processDue();
        return s;
    }

    /** Second poll: the baseline posting plus these. */
    private void pollAgain(UUID s, String token, Post... posts) {
        List<Post> all = new java.util.ArrayList<>();
        all.add(old("base", "Platform Engineer", JAVA_SQL));
        all.addAll(List.of(posts));
        serve(token, all);
        jdbc.update("UPDATE feed_source SET last_polled_at = NULL, content_hash = NULL, etag = NULL WHERE id = ?", s);
        pollOk(s);
    }

    // ------------------------------------------------------------------ 3. happy path

    @Test
    void baselineThenNewPostingQueuesExactlyOneNotification() {
        skills("Java", "SQL", "Kubernetes", "Python");
        profile("Java", "SQL");
        preferences(PREFS);
        notifications(true, 0.6);

        UUID s = lever("acme", "Acme");
        serve("acme", old("a", "Backend Engineer", JAVA_SQL), old("b", "Data Engineer", JAVA_SQL),
                old("c", "Platform Engineer", JAVA_SQL));
        pollOk(s);
        assertThat(processor.processDue()).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM feed_job WHERE baseline", Integer.class)).isEqualTo(3);
        assertThat(notificationCount()).as("baseline jobs never notify").isZero();
        assertThat(pendingProcessing()).isZero();

        serve("acme", old("a", "Backend Engineer", JAVA_SQL), old("b", "Data Engineer", JAVA_SQL),
                old("c", "Platform Engineer", JAVA_SQL), fresh("n", "Backend Engineer", BACKEND_DESC));
        jdbc.update("UPDATE feed_source SET last_polled_at = NULL WHERE id = ?", s);
        pollOk(s);
        assertThat(processor.processDue()).isGreaterThanOrEqualTo(1);
        UUID job = jobOf(s, "n");

        assertThat(jobSkills(job)).containsExactly(Map.entry("Java", true), Map.entry("Kubernetes", false),
                Map.entry("SQL", true));
        assertThat(ownerScore(job)).isCloseTo(0.8, within(1e-9));
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM feed_notification");
        assertThat(rows).hasSize(1);
        Map<String, Object> n = rows.get(0);
        assertThat(n.get("job_id")).isEqualTo(job);
        assertThat(n.get("status")).isEqualTo("PENDING");
        assertThat(n.get("channel")).isEqualTo("EMAIL");
        assertThat((Double) n.get("score")).isCloseTo(0.8, within(1e-9));
        assertThat(n.get("sent_at")).isNull();

        Map<String, Object> f = feedJob(job);
        assertThat(f.get("process_after")).isNull();
        assertThat(f.get("preference_verdict")).isEqualTo("PASS");
        assertThat(f.get("scored_profile_version")).isEqualTo(1);
        assertThat(f.get("scored_at")).isNotNull();
        assertThat(f.get("evaluated_preferences_version")).isEqualTo(1);
        assertThat(jsonStrings(f.get("filter_reasons"))).isEmpty();

        // idempotent: processing again writes nothing new
        Instant updated = updatedAt("job", job);
        jdbc.update("UPDATE feed_job SET process_after = now() WHERE job_id = ?", job);
        assertThat(processor.processDue()).isEqualTo(1);
        assertThat(notificationCount()).isEqualTo(1);
        assertThat(updatedAt("job", job)).as("job_skill unchanged → job.updated_at unchanged").isEqualTo(updated);
        assertThat(jobSkills(job)).hasSize(3);
        assertThat(processor.processDue()).as("nothing due").isZero();
    }

    // ------------------------------------------------------------------ 4. threshold, filters, freshness

    @Test
    void scoreBelowTheThresholdQueuesNothing() {
        skills("Java", "SQL", "Kubernetes", "Python");
        profile("Java", "SQL");
        notifications(true, 0.6);
        UUID s = baselined("acme");
        pollAgain(s, "acme", fresh("n", "Backend Engineer", "Java and Python are required. Kubernetes is a plus."));
        processor.processDue();
        UUID job = jobOf(s, "n");
        assertThat(jobSkills(job)).containsExactly(Map.entry("Java", true), Map.entry("Kubernetes", false),
                Map.entry("Python", true));
        assertThat(ownerScore(job)).isCloseTo(0.4, within(1e-9));
        assertThat(notificationCount()).isZero();
        assertThat(feedJob(job).get("process_after")).isNull();
    }

    @Test
    void filteredJobsAreStoredWithReasonsAndNotNotified() {
        skills("Java", "SQL");
        profile("Java", "SQL");
        preferences(PREFS);
        notifications(true, 0.6);
        UUID s = baselined("acme");
        pollAgain(s, "acme",
                fresh("r", "Backend Engineer", JAVA_SQL).at("remote", "US only", "US"),
                fresh("t", "Data Analyst", JAVA_SQL),
                fresh("k", "Sales Backend Engineer", JAVA_SQL),
                fresh("m", "Backend Developer", JAVA_SQL).at("onsite", "Head Office", null));
        processor.processDue();

        Map<String, Object> remote = feedJob(jobOf(s, "r"));
        assertThat(remote.get("preference_verdict")).isEqualTo("FILTERED");
        assertThat(jsonStrings(remote.get("filter_reasons"))).containsExactly("REMOTE_REGION");
        Map<String, Object> title = feedJob(jobOf(s, "t"));
        assertThat(title.get("preference_verdict")).isEqualTo("FILTERED");
        assertThat(jsonStrings(title.get("filter_reasons"))).containsExactly("TITLE");
        Map<String, Object> keyword = feedJob(jobOf(s, "k"));
        assertThat(keyword.get("preference_verdict")).isEqualTo("FILTERED");
        assertThat(jsonStrings(keyword.get("filter_reasons"))).containsExactly("EXCLUDED_KEYWORD");

        UUID missing = jobOf(s, "m");
        Map<String, Object> m = feedJob(missing);
        assertThat(m.get("preference_verdict")).as("missing data passes").isEqualTo("PASS");
        assertThat(jsonStrings(m.get("filter_flags"))).contains("LOCATION_UNKNOWN");

        // filtered jobs are still scored, but only the passing one is queued
        assertThat(ownerScore(jobOf(s, "r"))).isCloseTo(1.0, within(1e-9));
        assertThat(jdbc.queryForList("SELECT job_id FROM feed_notification", UUID.class)).containsExactly(missing);
    }

    @Test
    void staleAndClosedJobsAreNotNotified() {
        skills("Java", "SQL");
        profile("Java", "SQL");
        notifications(true, 0.6);
        UUID s = baselined("acme");
        pollAgain(s, "acme", fresh("old", "Backend Engineer", JAVA_SQL), fresh("gone", "Data Engineer", JAVA_SQL));
        UUID stale = jobOf(s, "old");
        UUID closed = jobOf(s, "gone");
        jdbc.update("UPDATE feed_job SET first_seen_at = now() - interval '25 hours' WHERE job_id = ?", stale);
        jdbc.update("UPDATE feed_job SET closed_at = now() WHERE job_id = ?", closed);
        processor.processDue();
        assertThat(ownerScore(stale)).isCloseTo(1.0, within(1e-9));
        assertThat(ownerScore(closed)).as("a closed job is still processed").isNotNull();
        assertThat(feedJob(stale).get("process_after")).isNull();
        assertThat(feedJob(closed).get("process_after")).isNull();
        assertThat(notificationCount()).isZero();
    }

    // ------------------------------------------------------------------ not matchable, no profile, no channel

    @Test
    void jobWithoutSkillsIsNotMatchable() {
        skills("Java", "SQL");
        profile("Java", "SQL");
        notifications(true, 0.0);
        UUID s = baselined("acme");
        pollAgain(s, "acme", fresh("o", "Office Manager", "Run the office and greet visitors."));
        processor.processDue();
        UUID job = jobOf(s, "o");
        assertThat(jobSkills(job)).isEmpty();
        assertThat(countMatchRows(job)).isZero();
        assertThat(notificationCount(job)).isZero();
        Map<String, Object> f = feedJob(job);
        assertThat(f.get("process_after")).isNull();
        assertThat(f.get("scored_at")).isNull();
        assertThat(f.get("preference_verdict")).isEqualTo("PASS");
        var detail = api.get("/api/feed/jobs/" + job).json();
        assertThat(detail.get("score").isNull()).isTrue();
        assertThat(detail.get("matchable").asBoolean()).isFalse();
    }

    @Test
    void withoutAProfileTheVerdictIsStoredAndNothingIsScored() {
        skills("Java", "SQL");
        notifications(true, 0.0);
        UUID s = baselined("acme");
        pollAgain(s, "acme", fresh("n", "Backend Engineer", JAVA_SQL));
        processor.processDue();
        UUID job = jobOf(s, "n");
        Map<String, Object> f = feedJob(job);
        assertThat(f.get("preference_verdict")).isEqualTo("PASS");
        assertThat(f.get("process_after")).isNull();
        assertThat(f.get("scored_profile_version")).isNull();
        assertThat(f.get("scored_at")).isNull();
        assertThat(jobSkills(job)).containsOnlyKeys("Java", "SQL");
        assertThat(countRows("job_match")).isZero();
        assertThat(notificationCount()).isZero();
    }

    @Test
    void unconfiguredChannelQueuesNothingAndLogsOneLine(CapturedOutput output) {
        StubNotifier.CONFIGURED.set(false);
        skills("Java", "SQL");
        profile("Java", "SQL");
        notifications(true, 0.6);
        UUID s = baselined("acme");
        pollAgain(s, "acme", fresh("z", "Zqxwing Backend Engineer", "Zqxdesc: Java and SQL."));
        processor.processDue();
        UUID job = jobOf(s, "z");
        assertThat(ownerScore(job)).isCloseTo(1.0, within(1e-9));
        assertThat(notificationCount()).isZero();
        String line = output.getAll().lines().filter(l -> l.contains("reason=email_not_configured"))
                .reduce((a, b) -> a + "\n" + b).orElse("");
        assertThat(line).contains("job=" + job).contains("score=1.0000");
        assertThat(line.lines().count()).isOne();
        assertThat(output.getAll()).doesNotContain("Zqxwing").doesNotContain("Zqxdesc");
    }

    @Test
    void disabledNotificationsQueueNothing() {
        skills("Java", "SQL");
        profile("Java", "SQL");
        UUID s = baselined("acme");          // no settings row: defaults (disabled)
        pollAgain(s, "acme", fresh("n", "Backend Engineer", JAVA_SQL));
        processor.processDue();
        assertThat(ownerScore(jobOf(s, "n"))).isCloseTo(1.0, within(1e-9));
        assertThat(notificationCount()).isZero();
    }

    @Test
    void processOneJobById() {
        skills("Java", "SQL");
        profile("Java", "SQL");
        notifications(true, 0.6);
        UUID s = baselined("acme");
        pollAgain(s, "acme", fresh("n", "Backend Engineer", JAVA_SQL));
        UUID job = jobOf(s, "n");
        assertThat(processor.process(job)).isTrue();
        assertThat(notificationCount(job)).isOne();
        assertThat(processor.process(job)).as("no longer pending").isFalse();
        assertThat(processor.process(UUID.randomUUID())).isFalse();
        // a job due in the future is still processed by process(id)
        jdbc.update("UPDATE feed_job SET process_after = ? WHERE job_id = ?",
                Timestamp.from(Instant.now().plus(Duration.ofHours(1))), job);
        assertThat(processor.processDue()).isZero();
        assertThat(processor.process(job)).isTrue();
        assertThat(notificationCount(job)).isOne();
    }
}
