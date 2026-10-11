package com.talentmatch.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.talentmatch.feed.PollOutcome;
import com.talentmatch.support.AbstractFeedProcessingIT;
import com.talentmatch.support.Api.Res;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * Processor concurrency: SKIP LOCKED claims, the match advisory lock (deferral by retry-delay), and
 * the known risk that marking all open jobs (preferences PUT) deadlocks with a concurrent poll.
 */
@ExtendWith(OutputCaptureExtension.class)
class FeedProcessorConcurrencyIT extends AbstractFeedProcessingIT {

    @org.springframework.beans.factory.annotation.Autowired
    private com.talentmatch.feed.skills.SkillDictionary dictionary;

    private static final String DESC = "Java and SQL.";

    /** A baselined source with {@code n} fresh jobs, all processed. */
    private UUID sourceWithJobs(String token, int n, String descriptionSuffix, boolean reversed) {
        UUID s = lever(token, "Acme");
        serve(token, posts(n, descriptionSuffix, reversed));
        pollOk(s);
        processor.processDue();
        return s;
    }

    private List<Post> posts(int n, String descriptionSuffix, boolean reversed) {
        List<Post> list = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            list.add(fresh(String.format("p%04d", i), "Backend Engineer " + i, DESC + descriptionSuffix));
        }
        if (reversed) {
            Collections.reverse(list);
        }
        return list;
    }

    /**
     * Row a is locked elsewhere for the whole first run. The run's opening refresh check (which has
     * something to mark: the vocabulary, profile and settings changed since the last check) waits at
     * most RUN_LOCK_TIMEOUT for a's row, gives up with a WARN, and the run still processes b.
     * The check rolled back, so the second run's check marks both open jobs: it processes a and b
     * (2, not 1), and b, re-processed, is not notified again.
     */
    @Test
    void jobLockedElsewhereIsSkippedAndProcessedLater(CapturedOutput output) throws Exception {
        skills("Java", "SQL");
        profile("Java", "SQL");
        notifications(true, 0.6);
        UUID s = lever("acme", "Acme");
        serve("acme", fresh("a", "Backend Engineer", DESC), fresh("b", "Data Engineer", DESC));
        pollOk(s);
        UUID a = jobOf(s, "a");
        UUID b = jobOf(s, "b");
        // Precondition independent of test order: the run's check must have something to mark.
        jdbc.update("INSERT INTO feed_state (id) VALUES (true) ON CONFLICT (id) DO NOTHING");
        jdbc.update("UPDATE feed_state SET skill_vocab_fingerprint = 'stale-for-test'");
        assertThat(feedStateText("skill_vocab_fingerprint")).isNotEqualTo(dictionary.refresh().fingerprint());
        Map<String, Object> stateBefore = jdbc.queryForMap("SELECT * FROM feed_state");

        try (Connection c = rawConnection()) {
            c.setAutoCommit(false);
            try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM feed_job WHERE job_id = ? FOR UPDATE")) {
                ps.setObject(1, a);
                ps.executeQuery().close();
            }
            int logStart = output.getAll().length();
            long t0 = System.nanoTime();
            assertThat(processor.processDue()).isEqualTo(1);
            assertThat(Duration.ofNanos(System.nanoTime() - t0)).as("never waits long for the lock")
                    .isLessThan(Duration.ofSeconds(5));
            assertThat(output.getAll().substring(logStart))
                    .contains("Feed processor: the refresh check lost a lock conflict");
            assertThat(jdbc.queryForMap("SELECT * FROM feed_state")).as("the timed-out check rolled back")
                    .isEqualTo(stateBefore);
            assertThat(feedJob(a).get("process_after")).isNotNull();
            assertThat(feedJob(b).get("process_after")).isNull();
            assertThat(notificationCount(b)).isOne();
            assertThat(notificationCount(a)).isZero();
            c.rollback();
        }
        // The first run's check rolled back: this run's check marks a and b, so both are processed.
        assertThat(processor.processDue()).isEqualTo(2);
        assertThat(feedJob(a).get("process_after")).isNull();
        assertThat(feedJob(b).get("process_after")).isNull();
        assertThat(notificationCount(a)).isOne();
        assertThat(notificationCount(b)).as("re-processed, not notified again").isOne();
        assertFeedStateUpToDate();
    }

    /**
     * A run whose opening check times out (one open job locked elsewhere) still processes the due
     * jobs on the other rows; the timed-out check leaves feed_state as it was (and marks nothing),
     * so the next run's check catches up.
     */
    @Test
    void runWhoseCheckTimesOutProcessesOtherJobsAndLeavesFeedStateUnchanged(CapturedOutput output) throws Exception {
        skills("Java", "SQL");
        profile("Java", "SQL");
        notifications(true, 0.6);
        UUID s = lever("acme", "Acme");
        serve("acme", fresh("a", "Backend Engineer", DESC), fresh("b", "Data Engineer", DESC),
                fresh("c", "Platform Engineer", DESC));
        pollOk(s);
        assertThat(processor.processDue()).isEqualTo(3);
        assertFeedStateUpToDate();
        UUID a = jobOf(s, "a");
        UUID b = jobOf(s, "b");
        UUID c = jobOf(s, "c");

        skill("Docker");                                    // vocabulary changed: the next check has work to do
        String fingerprintBefore = feedStateText("skill_vocab_fingerprint");
        Map<String, Object> stateBefore = jdbc.queryForMap("SELECT * FROM feed_state");
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("UPDATE feed_job SET process_after = ? WHERE job_id IN (?, ?)", now, b, c);
        String aRowBefore = jdbc.queryForObject("SELECT format('%s / %s', process_after, xmin) FROM feed_job "
                + "WHERE job_id = ?", String.class, a);

        try (Connection conn = rawConnection()) {
            conn.setAutoCommit(false);
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT 1 FROM feed_job WHERE job_id = ? FOR NO KEY UPDATE")) {
                ps.setObject(1, a);
                ps.executeQuery().close();
            }
            int logStart = output.getAll().length();
            long t0 = System.nanoTime();
            assertThat(processor.processDue()).as("b and c").isEqualTo(2);
            assertThat(Duration.ofNanos(System.nanoTime() - t0)).isLessThan(Duration.ofSeconds(5));
            assertThat(output.getAll().substring(logStart))
                    .contains("Feed processor: the refresh check lost a lock conflict");
            assertThat(jdbc.queryForMap("SELECT * FROM feed_state")).as("unchanged by the timed-out check")
                    .isEqualTo(stateBefore);
            assertThat(feedStateText("skill_vocab_fingerprint")).isEqualTo(fingerprintBefore);
            assertThat(feedJob(b).get("process_after")).isNull();
            assertThat(feedJob(c).get("process_after")).isNull();
            assertThat(jdbc.queryForObject("SELECT format('%s / %s', process_after, xmin) FROM feed_job "
                    + "WHERE job_id = ?", String.class, a)).as("a not marked").isEqualTo(aRowBefore);
            conn.rollback();
        }
        assertThat(processor.processDue()).as("the next check marks every open job").isEqualTo(3);
        assertThat(pendingProcessing()).isZero();
        assertThat(feedStateText("skill_vocab_fingerprint")).isNotEqualTo(fingerprintBefore);
        assertFeedStateUpToDate();
        assertThat(notificationCount()).as("one per job, ever").isEqualTo(3);
    }

    private String feedStateText(String column) {
        List<String> v = jdbc.queryForList("SELECT " + column + " FROM feed_state", String.class);
        return v.isEmpty() ? null : v.get(0);
    }

    /** feed_state holds the current profile and preferences versions and vocabulary fingerprint. */
    private void assertFeedStateUpToDate() {
        List<Integer> profile = jdbc.queryForList("SELECT version FROM owner_profile", Integer.class);
        List<Integer> prefs = jdbc.queryForList("SELECT version FROM job_preferences", Integer.class);
        assertThat(feedState("applied_profile_version")).isEqualTo(profile.isEmpty() ? null : profile.get(0));
        assertThat(feedState("applied_preferences_version")).isEqualTo(prefs.isEmpty() ? null : prefs.get(0));
        assertThat(feedStateText("skill_vocab_fingerprint")).isEqualTo(dictionary.refresh().fingerprint());
    }

    @Test
    void heldMatchLockDefersTheJobAndOthersFinish() throws Exception {
        skills("Java", "SQL");
        profile("Java", "SQL");
        notifications(true, 0.6);
        UUID s = lever("acme", "Acme");
        serve("acme", fresh("a", "Backend Engineer", DESC), fresh("b", "Data Engineer", DESC),
                fresh("c", "Platform Engineer", DESC));
        pollOk(s);
        UUID a = jobOf(s, "a");

        try (Connection c = rawConnection()) {
            holdMatchLock(c, a);
            Instant before = Instant.now();
            assertThat(processor.processDue()).isEqualTo(2);
            Instant retryAt = instant(feedJob(a).get("process_after"));
            assertThat(retryAt).as("deferred by retry-delay (1m)")
                    .isBetween(before.plus(Duration.ofSeconds(55)), Instant.now().plus(Duration.ofSeconds(61)));
            assertThat(countMatchRows(a)).isZero();
            assertThat(jobSkills(a)).as("rolled back with the job's transaction").isEmpty();
            assertThat(notificationCount(a)).isZero();
            assertThat(notificationCount()).isEqualTo(2);
            assertThat(processor.processDue()).as("not due before retry-delay").isZero();
        }
        // lock released: due again after the delay (simulated) and processed
        jdbc.update("UPDATE feed_job SET process_after = ? WHERE job_id = ?", Timestamp.from(Instant.now()), a);
        assertThat(processor.processDue()).isEqualTo(1);
        assertThat(ownerScore(a)).isCloseTo(1.0, within(1e-9));
        assertThat(notificationCount(a)).isOne();
    }

    // ------------------------------------------------------------------ deadlock reproduction

    private record RoundResult(String trigger, String poll, int putStatus, String putCode) {
    }

    /**
     * The implementer's flagged risk: PUT /api/preferences marks every open job (UPDATE feed_job …
     * WHERE closed_at IS NULL) while a poll rewrites the same jobs in another order. After the fix
     * (after-commit marker, job_id lock order, LockRetry, Deferred polls): every PUT is 2xx, no poll
     * is a source failure (Deferred is allowed), and the feed settles afterwards. Prints counts.
     */
    @Test
    void preferencesPutConcurrentWithPolls(CapturedOutput output) throws Exception {
        ch.qos.logback.classic.Logger retryLog =
                (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger("com.talentmatch.feed.LockRetry");
        ch.qos.logback.classic.Level previousLevel = retryLog.getLevel();
        retryLog.setLevel(ch.qos.logback.classic.Level.DEBUG);
        int logStart = output.getAll().length();
        long deadlocksBefore = pgDeadlocks();
        try {
            runRepro(output, logStart, deadlocksBefore);
        } finally {
            retryLog.setLevel(previousLevel);
        }
    }

    private void runRepro(CapturedOutput output, int logStart, long deadlocksBefore) throws Exception {
        int jobs = Integer.getInteger("deadlock.jobs", 300);
        int rounds = Integer.getInteger("deadlock.rounds", 4);
        skills("Java", "SQL");
        profile("Java", "SQL");
        UUID s = sourceWithJobs("acme", jobs, "", false);
        assertThat(pendingProcessing()).isZero();

        List<RoundResult> results = new ArrayList<>();
        for (int round = 1; round <= rounds; round++) {
            processor.processDue();                      // every row has process_after NULL again
            // every posting changes, served in the opposite order of the previous round
            serve("acme", posts(jobs, " Round " + round + ".", round % 2 == 1));
            jdbc.update("UPDATE feed_source SET last_polled_at = NULL WHERE id = ?", s);

            CountDownLatch go = new CountDownLatch(1);
            CompletableFuture<PollOutcome> poll = CompletableFuture.supplyAsync(() -> {
                await(go);
                return pollSync(s);
            });
            // odd rounds: fire the PUT once the poll's transaction is writing feed_job rows;
            // even rounds: at a random moment during the poll
            boolean onWrite = round % 2 == 1;
            long delayMs = ThreadLocalRandom.current().nextLong(0, 2500);
            String body = "{\"preferences\": {\"targetTitles\": [], \"excludedTitleKeywords\": [\"Round" + round + "\"]}}";
            CompletableFuture<Res> put = CompletableFuture.supplyAsync(() -> {
                await(go);
                if (onWrite) {
                    awaitPollWriting(poll);
                } else {
                    sleep(delayMs);
                }
                return api.put("/api/preferences", body);
            });
            go.countDown();
            PollOutcome po = poll.get(120, TimeUnit.SECONDS);
            Res pr = put.get(120, TimeUnit.SECONDS);
            String pollText = po instanceof PollOutcome.Ok ? "OK" : po instanceof PollOutcome.Deferred ? "Deferred" : po.toString();
            String code = pr.status() == 200 ? null : pr.json() == null ? pr.body() : pr.json().path("code").asText();
            results.add(new RoundResult(onWrite ? "on-write" : "random " + delayMs + "ms", pollText, pr.status(), code));
        }

        Map<String, Integer> tally = new TreeMap<>();
        results.forEach(r -> tally.merge((r.trigger().startsWith("on") ? "on-write " : "random ") + "put=" + r.putStatus() + (r.putCode() == null ? "" : "/" + r.putCode())
                + " poll=" + (r.poll().startsWith("OK") ? "OK" : r.poll()), 1, Integer::sum));
        System.out.println("DEADLOCK-REPRO jobs=" + jobs + " rounds=" + rounds + " tally=" + tally);
        results.forEach(r -> System.out.println("DEADLOCK-REPRO round " + r));

        String log = output.getAll().substring(logStart);
        long retries = log.lines().filter(l -> l.contains("lock conflict on attempt")).count();
        long markerGaveUp = log.lines().filter(l -> l.contains("Feed refresh after") && l.contains("failed")).count();
        long deferred = results.stream().filter(r -> r.poll().startsWith("Deferred")).count();
        long failedPuts = results.stream().filter(r -> r.putStatus() / 100 != 2).count();
        Thread.sleep(1500);                              // let the cumulative statistics flush
        long deadlocks = pgDeadlocks() - deadlocksBefore;
        System.out.println("DEADLOCK-REPRO summary deadlocks=" + deadlocks + " retries=" + retries
                + " markerGaveUp=" + markerGaveUp + " deferredPolls=" + deferred + " failedPuts=" + failedPuts);

        assertThat(results).as("every PUT succeeds").allSatisfy(r -> assertThat(r.putStatus()).isBetween(200, 299));
        assertThat(results).as("no source failures (Deferred is allowed)")
                .allSatisfy(r -> assertThat(r.poll()).doesNotStartWith("Failed"));

        // whatever happened, the feed converges: the next run reconciles and processes everything
        processor.processDue();
        assertThat(pendingProcessing()).isZero();
        Integer stored = jdbc.queryForObject("SELECT version FROM job_preferences", Integer.class);
        assertThat(feedState("applied_preferences_version")).isEqualTo(stored);
    }

    /** Waits (≤ 30s) until another backend holds a row-exclusive lock on feed_job, or the poll ended. */
    private void awaitPollWriting(CompletableFuture<?> poll) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (System.nanoTime() < deadline && !poll.isDone()) {
            Integer n = jdbc.queryForObject("SELECT count(*) FROM pg_locks l WHERE l.relation = 'feed_job'::regclass "
                    + "AND l.mode = 'RowExclusiveLock' AND l.granted AND l.pid <> pg_backend_pid()", Integer.class);
            if (n != null && n > 0) {
                return;
            }
            sleep(2);
        }
    }

    private long pgDeadlocks() {
        jdbc.execute("SELECT pg_stat_clear_snapshot()");
        return jdbc.queryForObject("SELECT deadlocks FROM pg_stat_database WHERE datname = current_database()",
                Long.class);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
