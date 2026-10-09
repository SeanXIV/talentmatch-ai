package com.talentmatch.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.talentmatch.support.AbstractFeedProcessingIT;
import com.talentmatch.support.Api.Res;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** §4.11 refresh triggers, §9.2 items 11, 12 (re-evaluation part) and 16. */
class FeedRefreshIT extends AbstractFeedProcessingIT {

    private static final String DESC = "You will build Java services and write SQL every day. Kubernetes is advantageous.";

    /** A baselined source plus one fresh "Backend Engineer" job, processed. */
    private UUID freshJob(String token, String description) {
        UUID s = lever(token, "Acme");
        serve(token, old("base", "Platform Engineer", "Java"));
        pollOk(s);
        serve(token, old("base", "Platform Engineer", "Java"), fresh("n", "Backend Engineer", description));
        jdbc.update("UPDATE feed_source SET last_polled_at = NULL WHERE id = ?", s);
        pollOk(s);
        processor.processDue();
        return jobOf(s, "n");
    }

    private int profileVersion() {
        return jdbc.queryForObject("SELECT version FROM owner_profile", Integer.class);
    }

    @Test
    void profileChangeRescoresWithoutASecondNotification() {
        skills("Java", "SQL", "Kubernetes");
        profile("Java", "SQL");
        notifications(true, 0.6);
        UUID job = freshJob("acme", DESC);
        assertThat(ownerScore(job)).isCloseTo(0.8, within(1e-9));
        assertThat(notificationCount()).isOne();
        assertThat(feedState("applied_profile_version")).isEqualTo(profileVersion());

        profile("Java", "SQL", "Kubernetes");
        int v = profileVersion();
        assertThat(v).isGreaterThan(1);
        assertThat(feedState("applied_profile_version")).as("set after commit").isEqualTo(v);
        assertThat(feedJob(job).get("process_after")).as("open job marked").isNotNull();

        processor.processDue();
        assertThat(ownerScore(job)).isCloseTo(1.0, within(1e-9));
        assertThat(feedJob(job).get("scored_profile_version")).isEqualTo(v);
        assertThat(notificationCount()).as("never a second row").isOne();
        assertThat(jdbc.queryForObject("SELECT score FROM feed_notification", Double.class))
                .as("the queued row keeps its score").isCloseTo(0.8, within(1e-9));
    }

    @Test
    void preferencesChangeFiltersOnTheNextProcess() {
        skills("Java", "SQL");
        profile("Java", "SQL");
        UUID job = freshJob("acme", "Java and SQL.");
        assertThat(feedJob(job).get("preference_verdict")).isEqualTo("PASS");

        preferences("{\"targetTitles\": [\"Data Engineer\"]}");
        int version = jdbc.queryForObject("SELECT version FROM job_preferences", Integer.class);
        assertThat(feedState("applied_preferences_version")).as("set by the after-commit marker before the response").isEqualTo(version);
        assertThat(feedJob(job).get("process_after")).isNotNull();

        notifications(true, 0.0);
        processor.processDue();
        var f = feedJob(job);
        assertThat(f.get("preference_verdict")).isEqualTo("FILTERED");
        assertThat(jsonStrings(f.get("filter_reasons"))).containsExactly("TITLE");
        assertThat(f.get("evaluated_preferences_version")).isEqualTo(version);
        assertThat(notificationCount()).isZero();

        // back to no title filter → PASS again
        preferences("{\"targetTitles\": []}");
        processor.processDue();
        assertThat(feedJob(job).get("preference_verdict")).isEqualTo("PASS");
        assertThat(feedJob(job).get("evaluated_preferences_version")).isEqualTo(version + 1);
    }

    @Test
    void newAliasAddsTheSkillAfterProcessing() {
        skills("Java");
        UUID pg = skill("PostgreSQL");
        profile("Java", "PostgreSQL");
        UUID job = freshJob("acme", "Java services on Postgres.");
        assertThat(jobSkills(job)).containsOnlyKeys("Java");

        Res r = api.post("/api/skills/" + pg + "/aliases", "{\"alias\":\"Postgres\"}");
        assertThat(r.status()).as("%s", r).isEqualTo(201);
        processor.processDue();
        assertThat(jobSkills(job)).containsOnlyKeys("Java", "PostgreSQL");
        assertThat(ownerScore(job)).isCloseTo(1.0, within(1e-9));
        assertThat(api.get("/api/feed/jobs/" + job).json().get("skills").findValuesAsText("name"))
                .containsExactly("Java", "PostgreSQL");
    }

    @Test
    void recoveryRemarksJobsWhenTheAppliedVersionIsBehind() {
        skills("Java", "SQL");
        profile("Java", "SQL");
        UUID job = freshJob("acme", "Java and SQL.");
        assertThat(pendingProcessing()).isZero();
        assertThat(refresh.reconcile()).as("nothing changed").isFalse();

        jdbc.update("UPDATE feed_state SET applied_profile_version = applied_profile_version - 1");
        recovery.recover();
        assertThat(feedJob(job).get("process_after")).isNotNull();
        assertThat(feedState("applied_profile_version")).isEqualTo(profileVersion());
        assertThat(processor.processDue()).isEqualTo(2);
        assertThat(pendingProcessing()).isZero();

        // null applied state (e.g. a fresh database) also marks
        jdbc.update("DELETE FROM feed_state");
        assertThat(refresh.reconcile()).isTrue();
        assertThat(pendingProcessing()).isEqualTo(2);
    }

    @Test
    void closedJobsAreNeverMarked() {
        skills("Java", "SQL");
        profile("Java", "SQL");
        UUID job = freshJob("acme", "Java and SQL.");
        jdbc.update("UPDATE feed_job SET closed_at = now() WHERE job_id = ?", job);

        profile("Java");
        preferences("{\"targetTitles\": [\"Data Engineer\"]}");
        jdbc.update("UPDATE feed_state SET applied_profile_version = NULL");
        recovery.recover();
        skill("Kubernetes");
        processor.processDue();

        assertThat(feedJob(job).get("process_after")).isNull();
        List<UUID> pending = jdbc.queryForList("SELECT job_id FROM feed_job WHERE process_after IS NOT NULL", UUID.class);
        assertThat(pending).doesNotContain(job);
        assertThat(feedJob(job).get("preference_verdict")).as("not re-evaluated").isEqualTo("PASS");
    }
}
