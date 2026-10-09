package com.talentmatch.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.talentmatch.feed.PollOutcome;
import com.talentmatch.support.AbstractFeedProcessingIT;
import com.talentmatch.support.Api.Res;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Lock conflicts after the deadlock fix: the preferences marker runs after commit (a PUT never fails
 * because of the feed), retries lock conflicts ({@code LockRetry}), and a poll that loses one is
 * Deferred without counting a failure. Failures are injected with a trigger that raises the given
 * SQLSTATE and counts its calls in a sequence (nextval survives the rollback).
 */
class FeedLockConflictIT extends AbstractFeedProcessingIT {

    @BeforeEach
    void createInjector() {
        jdbc.execute("DROP SEQUENCE IF EXISTS test_conflict_calls");
        jdbc.execute("CREATE SEQUENCE test_conflict_calls");
        jdbc.execute("""
                CREATE OR REPLACE FUNCTION test_conflict() RETURNS trigger AS $$
                BEGIN
                    PERFORM nextval('test_conflict_calls');
                    RAISE EXCEPTION 'injected conflict' USING ERRCODE = TG_ARGV[0];
                END $$ LANGUAGE plpgsql""");
    }

    @AfterEach
    void dropInjector() {
        clear("feed_state");
        clear("feed_job");
        jdbc.execute("DROP FUNCTION IF EXISTS test_conflict()");
        jdbc.execute("DROP SEQUENCE IF EXISTS test_conflict_calls");
    }

    private void inject(String table, String sqlState) {
        jdbc.execute("CREATE TRIGGER test_conflict BEFORE INSERT OR UPDATE ON " + table
                + " FOR EACH ROW EXECUTE FUNCTION test_conflict('" + sqlState + "')");
    }

    private void clear(String table) {
        jdbc.execute("DROP TRIGGER IF EXISTS test_conflict ON " + table);
    }

    private long calls() {
        return jdbc.queryForObject("SELECT CASE WHEN is_called THEN last_value ELSE 0 END FROM test_conflict_calls",
                Long.class);
    }

    /** A baselined source plus one processed fresh job. */
    private UUID processedJob(UUID source) {
        serve("acme", old("base", "Platform Engineer", "Java"));
        pollOk(source);
        serve("acme", old("base", "Platform Engineer", "Java"), fresh("n", "Backend Engineer", "Java and SQL."));
        jdbc.update("UPDATE feed_source SET last_polled_at = NULL WHERE id = ?", source);
        pollOk(source);
        processor.processDue();
        assertThat(pendingProcessing()).isZero();
        return jobOf(source, "n");
    }

    private int storedPreferencesVersion() {
        return jdbc.queryForObject("SELECT version FROM job_preferences", Integer.class);
    }

    // ------------------------------------------------------------------ preferences marker

    @Test
    void putSucceedsWhenMarkingFailsAndReconcileCatchesUp() {
        skills("Java", "SQL");
        profile("Java", "SQL");
        preferences("{}");
        UUID job = processedJob(lever("acme", "Acme"));
        assertThat(feedState("applied_preferences_version")).isEqualTo(1);

        inject("feed_state", "P0001");                 // not a lock conflict: no retry
        Res r = putPreferences("{\"targetTitles\": [\"Data Engineer\"]}");
        assertThat(r.status()).as("%s", r).isEqualTo(200);
        assertThat(storedPreferencesVersion()).as("the save committed").isEqualTo(2);
        assertThat(calls()).as("one attempt").isEqualTo(1);
        assertThat(feedState("applied_preferences_version")).as("lags").isEqualTo(1);
        assertThat(feedJob(job).get("process_after")).as("marking rolled back").isNull();
        clear("feed_state");

        assertThat(refresh.reconcile()).isTrue();
        assertThat(feedState("applied_preferences_version")).isEqualTo(2);
        assertThat(feedJob(job).get("process_after")).isNotNull();
        processor.processDue();
        assertThat(feedJob(job).get("preference_verdict")).isEqualTo("FILTERED");
        assertThat(feedJob(job).get("evaluated_preferences_version")).isEqualTo(2);
    }

    @Test
    void lockConflictInTheMarkerIsRetriedThenLeftToReconcile() {
        skills("Java", "SQL");
        profile("Java", "SQL");
        preferences("{}");
        UUID job = processedJob(lever("acme", "Acme"));

        inject("feed_state", "40P01");
        Res r = putPreferences("{\"targetTitles\": [\"Data Engineer\"]}");
        assertThat(r.status()).as("%s", r).isEqualTo(200);
        assertThat(calls()).as("three attempts in total").isEqualTo(3);
        assertThat(feedState("applied_preferences_version")).isEqualTo(1);

        // the profile marker behaves the same
        Res p = putProfile("Java");
        assertThat(p.status()).as("%s", p).isBetween(200, 201);
        assertThat(calls()).isEqualTo(6);

        // reconcile outside a transaction retries too, then rethrows
        assertThatThrownBy(() -> refresh.reconcile()).rootCause().hasMessageContaining("injected conflict");
        assertThat(calls()).isEqualTo(9);

        clear("feed_state");
        assertThat(refresh.reconcile()).isTrue();
        assertThat(feedState("applied_preferences_version")).isEqualTo(2);
        assertThat(feedState("applied_profile_version"))
                .isEqualTo(jdbc.queryForObject("SELECT version FROM owner_profile", Integer.class));
        assertThat(feedJob(job).get("process_after")).isNotNull();
    }

    // ------------------------------------------------------------------ poll

    @Test
    void pollThatLosesALockConflictIsDeferredWithoutAFailure() {
        skills("Java", "SQL");
        UUID s = lever("acme", "Acme");
        UUID job = processedJob(s);
        String hashBefore = (String) posting(s, "n").get("content_hash");
        jdbc.update("UPDATE feed_source SET consecutive_failures = 2, last_status = 'ERROR', "
                + "last_error = 'earlier error', last_polled_at = NULL WHERE id = ?", s);

        serve("acme", old("base", "Platform Engineer", "Java"),
                fresh("n", "Backend Engineer", "Java and SQL. Changed text."));
        inject("feed_job", "40P01");
        Instant before = Instant.now();
        PollOutcome outcome = pollSync(s);
        Instant after = Instant.now();
        assertThat(calls()).isPositive();
        clear("feed_job");

        assertThat(outcome).isInstanceOf(PollOutcome.Deferred.class);
        Instant retryAt = ((PollOutcome.Deferred) outcome).retryAt();
        assertThat(retryAt).isBetween(before.plusSeconds(5), after.plusSeconds(10));
        Map<String, Object> row = sourceRow(s);
        assertThat(row.get("consecutive_failures")).isEqualTo(2);
        assertThat(row.get("last_status")).isEqualTo("ERROR");
        assertThat(row.get("last_error")).isEqualTo("earlier error");
        assertThat(row.get("lease_until")).isNull();
        assertThat(Duration.between(retryAt, instant(row.get("next_poll_at"))).abs())
                .isLessThan(Duration.ofMillis(2));
        assertThat(posting(s, "n").get("content_hash")).as("rolled back").isEqualTo(hashBefore);
        assertThat(feedJob(job).get("process_after")).isNull();

        // the retry succeeds
        PollOutcome again = pollSync(s);
        assertThat(again).isInstanceOf(PollOutcome.Ok.class);
        assertThat(posting(s, "n").get("content_hash")).isNotEqualTo(hashBefore);
        assertThat(sourceRow(s).get("consecutive_failures")).isEqualTo(0);
    }

    @Test
    void otherSaveErrorsAreStillFailures() {
        skills("Java", "SQL");
        UUID s = lever("acme", "Acme");
        processedJob(s);
        serve("acme", old("base", "Platform Engineer", "Java"),
                fresh("n", "Backend Engineer", "Java and SQL. Changed text."));
        jdbc.update("UPDATE feed_source SET last_polled_at = NULL WHERE id = ?", s);
        inject("feed_job", "P0001");
        PollOutcome outcome = pollSync(s);
        clear("feed_job");
        assertThat(outcome).isInstanceOf(PollOutcome.Failed.class);
        assertThat(sourceRow(s).get("consecutive_failures")).isEqualTo(1);
    }
}
