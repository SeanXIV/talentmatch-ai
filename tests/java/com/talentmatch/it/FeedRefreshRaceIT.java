package com.talentmatch.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.talentmatch.feed.FeedJobRepository;
import com.talentmatch.support.AbstractFeedProcessingIT;
import com.talentmatch.support.Api.Res;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import com.talentmatch.feed.FeedStateRepository;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.ResultSet;
import java.sql.Statement;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The refresh-marker / processor race: a preferences save or profile confirm that commits while a
 * processor run is in progress must leave no open job evaluated against the older version
 * (per-job inputs read under the job's lock; {@code markOpenForProcessing} re-marks every open job,
 * already-due ones included, after waiting for the processor's row lock).
 *
 * <p>Pausing the processor mid-job is deterministic: a separate connection inserts (uncommitted) the
 * {@code feed_notification} row of the job, so the processor's {@code INSERT … ON CONFLICT DO NOTHING}
 * for that job waits on it, after the job's inputs were read and while its row lock is held. Waiting
 * states are detected through {@code pg_stat_activity}, never by sleeping.
 */
class FeedRefreshRaceIT extends AbstractFeedProcessingIT {

    private static final String DESC = "Java and SQL.";
    private static final String MARKER_SQL = "LEAST(COALESCE";

    @Autowired
    private FeedJobRepository jobRepository;
    @Autowired
    private FeedStateRepository stateRepository;
    @Autowired
    private DataSource dataSource;
    @Autowired
    private PlatformTransactionManager transactionManager;

    // ------------------------------------------------------------------ helpers

    /** A source with these fresh postings, polled (all due, none processed). Returns job ids in processing order. */
    private List<UUID> dueJobs(String token, String... titles) {
        UUID s = lever(token, "Acme");
        List<Post> posts = new ArrayList<>();
        for (int i = 0; i < titles.length; i++) {
            posts.add(fresh("p" + i, titles[i], DESC));
        }
        serve(token, posts);
        pollOk(s);
        assertThat(pendingProcessing()).isEqualTo(titles.length);
        // the processor's order (FeedJobRepository#findDue)
        return jdbc.queryForList("SELECT job_id FROM feed_job WHERE process_after IS NOT NULL "
                + "ORDER BY process_after, job_id", UUID.class);
    }

    /** Inserts X's notification row without committing: the processor's insert for X then waits. */
    private static void blockNotificationInsert(Connection c, UUID jobId) throws Exception {
        c.setAutoCommit(false);
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO feed_notification (job_id, channel, score) VALUES (?, 'EMAIL', 0.5)")) {
            ps.setObject(1, jobId);
            ps.executeUpdate();
        }
    }

    /** Waits until another backend is waiting on a lock while running a statement containing {@code fragment}. */
    private void awaitLockWait(String fragment, CompletableFuture<?>... notDone) {
        await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(5)).until(() -> {
            for (CompletableFuture<?> f : notDone) {
                if (f.isDone()) {
                    f.join();                       // surfaces an exception early
                    throw new AssertionError("finished before waiting on a lock: " + fragment);
                }
            }
            Integer n = jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE pid <> pg_backend_pid() "
                    + "AND wait_event_type = 'Lock' AND query LIKE ?", Integer.class, "%" + fragment + "%");
            return n != null && n > 0;
        });
    }

    private void drain() {
        for (int i = 0; i < 20 && processor.processDue() > 0; i++) {
            // until nothing is due
        }
        assertThat(pendingProcessing()).as("nothing left due").isZero();
    }

    /** Row identity for "untouched": an UPDATE would bump updated_at (trigger) or process_after. */
    private String processAfterAndUpdatedAt(UUID jobId) {
        return jdbc.queryForObject("SELECT format('%s / %s / %s', process_after, updated_at, xmin) "
                + "FROM feed_job WHERE job_id = ?", String.class, jobId);
    }

    private Integer preferencesVersion() {
        return jdbc.queryForObject("SELECT version FROM job_preferences", Integer.class);
    }

    private Integer profileVersion() {
        return jdbc.queryForObject("SELECT version FROM owner_profile", Integer.class);
    }

    private static String prefsExcluding(String keyword) {
        return "{\"targetTitles\": [], \"excludedTitleKeywords\": [" + (keyword == null ? "" : "\"" + keyword + "\"")
                + "]}";
    }

    // ------------------------------------------------------------------ a. preferences saved mid-run

    @Test
    void preferencesSavedMidRunLeaveNoJobOnTheOldVersion() throws Exception {
        skills("Java", "SQL");
        profile("Java", "SQL");
        notifications(true, 0.6);
        preferences(prefsExcluding(null));
        Integer v1 = preferencesVersion();
        List<UUID> order = dueJobs("acme", "Backend Engineer 0", "Data Engineer 1", "Backend Engineer 2",
                "Data Engineer 3", "Backend Engineer 4", "Data Engineer 5");
        UUID x = order.get(2);                                  // jobs before it commit with v1, after it are due
        String xTitle = (String) job(x).get("title");
        String excluded = xTitle.startsWith("Backend") ? "Backend" : "Data";

        CompletableFuture<Integer> run;
        CompletableFuture<Res> put;
        try (Connection c = rawConnection()) {
            blockNotificationInsert(c, x);
            run = CompletableFuture.supplyAsync(processor::processDue);
            awaitLockWait("INSERT INTO feed_notification", run);   // x locked, its inputs (v1) read

            put = CompletableFuture.supplyAsync(() -> putPreferences(prefsExcluding(excluded)));
            awaitLockWait(MARKER_SQL, put, run);                     // marker waits for x's row lock
            assertThat(preferencesVersion()).as("the save committed").isGreaterThan(v1);
            c.rollback();                                            // x's transaction goes on and commits
        }
        Res r = put.get(60, TimeUnit.SECONDS);
        assertThat(r.status()).as("PUT /api/preferences %s", r).isEqualTo(200);
        run.get(60, TimeUnit.SECONDS);
        Integer v2 = preferencesVersion();

        // the race happened: x (and the jobs before it) were committed with v1, and are pending again
        for (int i = 0; i <= 2; i++) {
            Map<String, Object> f = feedJob(order.get(i));
            assertThat(f.get("evaluated_preferences_version")).as("job %d evaluated during the run", i).isEqualTo(v1);
            assertThat(f.get("process_after")).as("job %d re-marked", i).isNotNull();
        }
        assertThat(feedState("applied_preferences_version")).isEqualTo(v2);

        drain();

        for (UUID id : order) {
            Map<String, Object> f = feedJob(id);
            String title = (String) job(id).get("title");
            assertThat(f.get("evaluated_preferences_version")).as("version of %s", title).isEqualTo(v2);
            assertThat(f.get("preference_verdict")).as("verdict of %s under v2", title)
                    .isEqualTo(title.startsWith(excluded) ? "FILTERED" : "PASS");
        }
        assertThat(feedJob(x).get("preference_verdict")).as("x flipped from PASS").isEqualTo("FILTERED");
        assertThat(feedState("applied_preferences_version")).isEqualTo(v2);
        assertThat(notificationCount(x)).as("x was notified once, under v1").isOne();
    }

    // ------------------------------------------------------------------ b. profile confirmed mid-run

    @Test
    void profileConfirmedMidRunLeavesNoJobScoredAgainstTheOldVersion() throws Exception {
        skills("Java", "SQL");
        profile("Java", "SQL");
        notifications(true, 0.6);
        Integer v1 = profileVersion();
        List<UUID> order = dueJobs("acme", "Backend Engineer 0", "Backend Engineer 1", "Backend Engineer 2",
                "Backend Engineer 3", "Backend Engineer 4");
        UUID x = order.get(2);

        CompletableFuture<Integer> run;
        CompletableFuture<Res> put;
        try (Connection c = rawConnection()) {
            blockNotificationInsert(c, x);
            run = CompletableFuture.supplyAsync(processor::processDue);
            awaitLockWait("INSERT INTO feed_notification", run);

            put = CompletableFuture.supplyAsync(() -> putProfile("Java"));
            awaitLockWait(MARKER_SQL, put, run);
            assertThat(profileVersion()).as("the confirm committed").isGreaterThan(v1);
            c.rollback();
        }
        Res r = put.get(60, TimeUnit.SECONDS);
        assertThat(r.status()).as("PUT /api/profile %s", r).isBetween(200, 201);
        run.get(60, TimeUnit.SECONDS);
        Integer v2 = profileVersion();

        for (int i = 0; i <= 2; i++) {
            Map<String, Object> f = feedJob(order.get(i));
            assertThat(f.get("scored_profile_version")).as("job %d scored during the run", i).isEqualTo(v1);
            assertThat(f.get("process_after")).as("job %d re-marked", i).isNotNull();
        }
        assertThat(feedState("applied_profile_version")).isEqualTo(v2);

        drain();

        for (UUID id : order) {
            assertThat(jobSkills(id)).as("job has skills").isNotEmpty();
            Map<String, Object> f = feedJob(id);
            assertThat(f.get("scored_profile_version")).isEqualTo(v2);
            assertThat(ownerScore(id)).as("scored with Java only").isNotNull().isLessThan(1.0);
        }
        assertThat(feedState("applied_profile_version")).isEqualTo(v2);
    }

    // ------------------------------------------------------------------ c. markOpenForProcessing

    @Test
    void markOpenKeepsEarlierDueTimesAndSkipsClosedJobs() {
        skills("Java", "SQL");
        List<UUID> ids = dueJobs("acme", "A", "B", "C", "D", "E");
        processor.processDue();
        assertThat(pendingProcessing()).isZero();
        UUID due = ids.get(0);
        UUID none = ids.get(1);
        UUID future = ids.get(2);
        UUID closed = ids.get(3);
        UUID closedPending = ids.get(4);

        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        Instant earlier = now.minus(Duration.ofHours(1));
        Instant later = now.plus(Duration.ofHours(1));
        jdbc.update("UPDATE feed_job SET process_after = ? WHERE job_id = ?", Timestamp.from(earlier), due);
        jdbc.update("UPDATE feed_job SET process_after = ? WHERE job_id = ?", Timestamp.from(later), future);
        jdbc.update("UPDATE feed_job SET closed_at = ? WHERE job_id = ?", Timestamp.from(earlier), closed);
        jdbc.update("UPDATE feed_job SET closed_at = ?, process_after = ? WHERE job_id = ?", Timestamp.from(earlier),
                Timestamp.from(later), closedPending);
        String closedBefore = processAfterAndUpdatedAt(closed);
        String closedPendingBefore = processAfterAndUpdatedAt(closedPending);

        int n = jobRepository.markOpenForProcessing(now);

        assertThat(n).as("every open job, the already-due one included").isEqualTo(3);
        assertThat(instant(feedJob(due).get("process_after"))).as("keeps its place").isEqualTo(earlier);
        assertThat(instant(feedJob(none).get("process_after"))).isEqualTo(now);
        assertThat(instant(feedJob(future).get("process_after"))).isEqualTo(now);
        assertThat(processAfterAndUpdatedAt(closed)).as("closed: untouched").isEqualTo(closedBefore);
        assertThat(processAfterAndUpdatedAt(closedPending)).as("closed: untouched").isEqualTo(closedPendingBefore);
        assertThat(instant(feedJob(closedPending).get("process_after"))).isEqualTo(later);
    }

    // ------------------------------------------------------------------ d. marker waits for a row lock

    @Test
    void markerWaitsForTheRowLockAndRemarksAJobClearedMeanwhile() throws Exception {
        skills("Java", "SQL");
        List<UUID> ids = dueJobs("acme", "A", "B", "C");
        processor.processDue();
        UUID x = ids.get(1);
        jdbc.update("UPDATE feed_job SET process_after = ? WHERE job_id = ?",
                Timestamp.from(Instant.now().minus(Duration.ofHours(1))), x);
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);

        CompletableFuture<Integer> marker;
        try (Connection c = rawConnection()) {
            c.setAutoCommit(false);
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT 1 FROM feed_job WHERE job_id = ? FOR NO KEY UPDATE")) {
                ps.setObject(1, x);
                ps.executeQuery().close();
            }
            marker = CompletableFuture.supplyAsync(() -> jobRepository.markOpenForProcessing(now));
            awaitLockWait(MARKER_SQL, marker);
            assertThat(marker).as("blocked by the row lock").isNotDone();
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE feed_job SET process_after = NULL WHERE job_id = ?")) {
                ps.setObject(1, x);
                ps.executeUpdate();
            }
            c.commit();                                     // as a processor clearing the job would
        }
        assertThat(marker.get(30, TimeUnit.SECONDS)).isEqualTo(3);
        assertThat(instant(feedJob(x).get("process_after"))).as("re-marked from its latest version, not NULL")
                .isEqualTo(now);
        for (UUID id : ids) {
            assertThat(instant(feedJob(id).get("process_after"))).isEqualTo(now);
        }
    }

    @Test
    void markerSkipsAJobClosedWhileItWaited() throws Exception {
        skills("Java", "SQL");
        List<UUID> ids = dueJobs("acme", "A", "B", "C");
        processor.processDue();
        UUID x = ids.get(1);
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);

        CompletableFuture<Integer> marker;
        try (Connection c = rawConnection()) {
            c.setAutoCommit(false);
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE feed_job SET closed_at = now() WHERE job_id = ?")) {
                ps.setObject(1, x);
                ps.executeUpdate();
            }
            marker = CompletableFuture.supplyAsync(() -> jobRepository.markOpenForProcessing(now));
            awaitLockWait(MARKER_SQL, marker);
            c.commit();
        }
        assertThat(marker.get(30, TimeUnit.SECONDS)).as("only the jobs still open").isEqualTo(2);
        assertThat(feedJob(x).get("process_after")).isNull();
    }

    // ------------------------------------------------------------------ e. match lock held: job_skill unchanged

    @Test
    void heldMatchLockDefersWithoutChangingExistingJobSkills() throws Exception {
        skills("Java", "SQL");
        profile("Java", "SQL");
        UUID s = lever("acme", "Acme");
        serve("acme", fresh("a", "Backend Engineer", "Java, SQL and Docker."));
        pollOk(s);
        processor.processDue();
        UUID a = jobOf(s, "a");
        assertThat(jobSkills(a)).containsOnlyKeys("Java", "SQL");

        skill("Docker");                                    // vocabulary change: the next run re-marks the job
        try (Connection c = rawConnection()) {
            holdMatchLock(c, a);
            Instant before = Instant.now();
            assertThat(processor.processDue()).isZero();
            Instant retryAt = instant(feedJob(a).get("process_after"));
            assertThat(retryAt).as("deferred by retry-delay (1m)")
                    .isBetween(before.plus(Duration.ofSeconds(55)), Instant.now().plus(Duration.ofSeconds(61)));
            assertThat(jobSkills(a)).as("unchanged while the match lock is held").containsOnlyKeys("Java", "SQL");
        }
        jdbc.update("UPDATE feed_job SET process_after = ? WHERE job_id = ?", Timestamp.from(Instant.now()), a);
        assertThat(processor.processDue()).isEqualTo(1);
        assertThat(jobSkills(a)).containsOnlyKeys("Docker", "Java", "SQL");
    }

    // ------------------------------------------------------------------ f. bounded marker lock waits

    /** Takes a FOR NO KEY UPDATE lock on the job's row in an open transaction of {@code c}. */
    private static void holdRowLock(Connection c, UUID jobId) throws Exception {
        c.setAutoCommit(false);
        try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM feed_job WHERE job_id = ? FOR NO KEY UPDATE")) {
            ps.setObject(1, jobId);
            ps.executeQuery().close();
        }
    }

    /**
     * The after-commit preferences marker is blocked by a row lock that is never released during the
     * PUT: each attempt gives up after MARKER_LOCK_TIMEOUT, the PUT still returns 200 within a few
     * seconds, and feed_state keeps the old applied version (the marker rolled back). Once the lock
     * is gone, the next processor run's check catches up and every open job ends on the new version.
     */
    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void preferencesPutWithMarkerBlockedReturnsAndTheProcessorCatchesUpLater(CapturedOutput output) throws Exception {
        skills("Java", "SQL");
        profile("Java", "SQL");
        preferences(prefsExcluding(null));
        Integer v1 = preferencesVersion();
        List<UUID> ids = dueJobs("acme", "Backend Engineer 0", "Data Engineer 1", "Backend Engineer 2");
        processor.processDue();
        assertThat(pendingProcessing()).isZero();
        assertThat(feedState("applied_preferences_version")).isEqualTo(v1);
        UUID x = ids.get(1);
        int logStart = output.getAll().length();

        Res r;
        Duration took;
        try (Connection c = rawConnection()) {
            holdRowLock(c, x);
            long t0 = System.nanoTime();
            CompletableFuture<Res> put = CompletableFuture.supplyAsync(() -> putPreferences(prefsExcluding("Backend")));
            awaitLockWait(MARKER_SQL, put);                          // the marker is blocked by our holder
            r = put.get(30, TimeUnit.SECONDS);                       // returns while the lock is still held
            took = Duration.ofNanos(System.nanoTime() - t0);

            assertThat(r.status()).as("PUT /api/preferences %s", r).isEqualTo(200);
            assertThat(took).as("3 attempts of at most 2s each").isLessThan(Duration.ofSeconds(10));
            Integer v2 = preferencesVersion();
            assertThat(v2).as("the save committed").isGreaterThan(v1);
            assertThat(feedState("applied_preferences_version")).as("marker rolled back: lags").isEqualTo(v1);
            assertThat(pendingProcessing()).as("no job marked").isZero();
            assertThat(output.getAll().substring(logStart))
                    .contains("Feed refresh after preferences version " + v2 + " failed");
            c.rollback();
        }

        Integer v2 = preferencesVersion();
        drain();
        for (UUID id : ids) {
            Map<String, Object> f = feedJob(id);
            String title = (String) job(id).get("title");
            assertThat(f.get("evaluated_preferences_version")).as("version of %s", title).isEqualTo(v2);
            assertThat(f.get("preference_verdict")).as("verdict of %s", title)
                    .isEqualTo(title.startsWith("Backend") ? "FILTERED" : "PASS");
        }
        assertThat(feedState("applied_preferences_version")).isEqualTo(v2);
    }

    /**
     * limitLockWait sets lock_timeout for the current transaction only: after markers and checks
     * that succeeded, and after one that timed out and rolled back, every pooled connection has the
     * server's default lock_timeout again.
     */
    @Test
    void markerLockTimeoutDoesNotLeakIntoPooledConnections() throws Exception {
        String serverDefault;
        try (Connection c = rawConnection(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SHOW lock_timeout")) {
            rs.next();
            serverDefault = rs.getString(1);
        }
        // positive control: inside a transaction the bound is in effect
        String inside = new TransactionTemplate(transactionManager).execute(status -> {
            stateRepository.limitLockWait(Duration.ofMillis(1234));
            return jdbc.queryForObject("SHOW lock_timeout", String.class);
        });
        assertThat(inside).isEqualTo("1234ms");

        skills("Java", "SQL");
        List<UUID> ids = dueJobs("acme", "A", "B", "C");
        skill("Kotlin");
        assertThat(refresh.reconcile()).as("reconcile with a change (vocabulary)").isTrue();
        processor.processDue();
        preferences(prefsExcluding("Nothing"));                   // after-commit marker, succeeds
        skill("Docker");
        try (Connection c = rawConnection()) {
            holdRowLock(c, ids.get(0));
            processor.processDue();                                // its check times out and rolls back
            c.rollback();
        }

        HikariDataSource pool = dataSource.unwrap(HikariDataSource.class);
        List<Connection> borrowed = new ArrayList<>();
        try {
            for (int i = 0; i < pool.getMaximumPoolSize(); i++) {
                borrowed.add(pool.getConnection());
            }
            for (Connection c : borrowed) {
                try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SHOW lock_timeout")) {
                    rs.next();
                    assertThat(rs.getString(1)).as("lock_timeout of a pooled connection").isEqualTo(serverDefault);
                }
            }
        } finally {
            for (Connection c : borrowed) {
                c.close();
            }
        }
    }
}
