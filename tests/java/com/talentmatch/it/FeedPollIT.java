package com.talentmatch.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.talentmatch.feed.PollOutcome;
import com.talentmatch.support.AbstractFeedIT;
import com.talentmatch.support.RouteStubServer.Reply;
import java.sql.Array;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * §9.2 step-6 items: baseline, new and changed postings, closing (with the suspicious-drop guard),
 * reopening, cross-source dedup and conditional fetches. Polls are driven with
 * {@code SourcePoller.poll} on the test thread, or {@code FeedScheduler.tick()}.
 */
class FeedPollIT extends AbstractFeedIT {

    private static final String DESC = "We build payment rails. You will own Java services and PostgreSQL.";

    /** Fixed per test, so a posting built twice has the same publish time (and content hash). */
    private final Instant start = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);

    private Instant now() {
        return start;
    }

    private LeverJob old(String id, String title) {
        return new LeverJob(id, title, DESC + " " + id, now().minus(Duration.ofDays(10)));
    }

    private LeverJob fresh(String id, String title) {
        return new LeverJob(id, title, DESC + " " + id, now().minus(Duration.ofHours(1)));
    }

    // ------------------------------------------------------------------ 1. baseline

    @Test
    void firstPollMarksOldPostingsBaselineButNotFreshOnes() {
        UUID s = lever("acme", "Acme");
        leverReplies("acme", old("a", "Backend Engineer"), fresh("b", "Data Engineer"),
                new LeverJob("c", "Platform Engineer", DESC, null));
        PollOutcome.Ok ok = pollOk(s);
        assertThat(ok.status()).isEqualTo("OK");
        assertThat(ok.stats().created()).isEqualTo(3);

        assertThat(posting(s, "a").get("baseline")).isEqualTo(true);
        assertThat(posting(s, "c").get("baseline")).as("no publish time = baseline").isEqualTo(true);
        assertThat(posting(s, "b").get("baseline")).isEqualTo(false);
        assertThat(feedJob(jobOf(s, "a")).get("baseline")).isEqualTo(true);
        assertThat(feedJob(jobOf(s, "b")).get("baseline")).isEqualTo(false);

        Map<String, Object> row = sourceRow(s);
        assertThat(row.get("baseline_at")).isNotNull();
        assertThat(row.get("open_postings")).isEqualTo(3);
        assertThat(row.get("last_status")).isEqualTo("OK");
        assertThat(row.get("lease_until")).isNull();
        assertThat(row.get("consecutive_failures")).isEqualTo(0);
        assertThat(instant(row.get("next_poll_at"))).isAfter(now().plusSeconds(200));
        assertThat(row.get("content_hash")).isNotNull();
    }

    // ------------------------------------------------------------------ 2. new posting

    @Test
    void postingAfterTheFirstPollIsNewWithJobFieldsFilled() {
        UUID s = lever("acme", "Acme");
        leverReplies("acme", old("a", "Backend Engineer"));
        pollOk(s);
        Instant baselineAt = instant(sourceRow(s).get("baseline_at"));
        jdbc.update("UPDATE feed_job SET process_after = NULL");

        // an old publish time: still new, since the source has its baseline already
        leverReplies("acme", old("a", "Backend Engineer"), old("n", "Sr. Data Engineer"));
        PollOutcome.Ok ok = pollOk(s);
        assertThat(ok.stats().created()).isEqualTo(1);
        assertThat(ok.stats().updated()).isZero();

        assertThat(posting(s, "n").get("baseline")).isEqualTo(false);
        UUID jobId = jobOf(s, "n");
        Map<String, Object> fj = feedJob(jobId);
        assertThat(fj.get("baseline")).isEqualTo(false);
        assertThat(fj.get("process_after")).as("handed to the processor").isNotNull();
        assertThat(fj.get("primary_url")).isEqualTo("https://jobs.lever.co/acme/n");
        assertThat(fj.get("workplace")).isEqualTo("REMOTE");
        assertThat(fj.get("seniority")).isEqualTo("SENIOR");
        assertThat(fj.get("location_text")).isEqualTo("Cape Town");
        assertThat(fj.get("employment_type")).isEqualTo("Full-time");
        assertThat(fj.get("posted_at")).isNotNull();
        assertThat(fj.get("description_hash")).isNotNull();
        assertThat(fj.get("dedup_key")).isEqualTo("acme|senior data engineer|remote");
        assertThat(countryCodes(jobId)).containsExactly("ZA");
        assertThat(fj.get("enrichment_status")).isEqualTo("PENDING");

        Map<String, Object> job = job(jobId);
        assertThat(job.get("origin")).isEqualTo("FEED");
        assertThat(job.get("title")).isEqualTo("Sr. Data Engineer");
        assertThat(job.get("company")).isEqualTo("Acme");
        assertThat((String) job.get("description")).startsWith(DESC);

        // the untouched job is not handed over again
        assertThat(feedJob(jobOf(s, "a")).get("process_after")).isNull();
        assertThat(instant(sourceRow(s).get("baseline_at"))).isEqualTo(baselineAt);
    }

    private List<String> countryCodes(UUID jobId) {
        return jdbc.query("SELECT country_codes FROM feed_job WHERE job_id = ?", (rs, n) -> {
            Array a = rs.getArray(1);
            return Arrays.stream((Object[]) a.getArray()).map(o -> o.toString().strip()).toList();
        }, jobId).get(0);
    }

    // ------------------------------------------------------------------ 3. changed posting

    @Test
    void changedContentUpdatesJobAndResetsEnrichment() {
        UUID s = lever("acme", "Acme");
        LeverJob a = old("a", "Backend Engineer");
        leverReplies("acme", a);
        pollOk(s);
        UUID jobId = jobOf(s, "a");
        jdbc.update("UPDATE feed_job SET enrichment_status = 'SUCCEEDED', ai_skills = '[]', enrichment_model = 'm', "
                + "enriched_at = now(), enrichment_attempts = 2, process_after = NULL WHERE job_id = ?", jobId);

        leverReplies("acme", a.withDescription("Completely new description of the role."));
        PollOutcome.Ok ok = pollOk(s);
        assertThat(ok.stats().updated()).isEqualTo(1);
        assertThat(ok.stats().created()).isZero();

        assertThat(posting(s, "a").get("description")).isEqualTo("Completely new description of the role.");
        assertThat(job(jobId).get("description")).isEqualTo("Completely new description of the role.");
        Map<String, Object> fj = feedJob(jobId);
        assertThat(fj.get("enrichment_status")).isEqualTo("PENDING");
        assertThat(fj.get("enrichment_attempts")).isEqualTo(0);
        assertThat(fj.get("process_after")).isNotNull();
    }

    @Test
    void unchangedContentWithADifferentBodyKeepsJobUpdatedAt() {
        UUID s = lever("acme", "Acme");
        LeverJob a = old("a", "Backend Engineer");
        leverReplies("acme", a);
        pollOk(s);
        UUID jobId = jobOf(s, "a");
        Instant jobUpdated = updatedAt("job", jobId);
        Instant lastSeen = instant(posting(s, "a").get("last_seen_at"));

        // same posting content, but a different body (an extra field) so the body hash differs
        String body = leverBody(a).replace("\"id\":\"a\"", "\"id\":\"a\",\"extra\":\"noise\"");
        STUB.route(leverPath("acme"), Reply.json(200, body));
        PollOutcome.Ok ok = pollOk(s);
        assertThat(ok.stats().updated()).isZero();
        assertThat(ok.stats().created()).isZero();
        assertThat(updatedAt("job", jobId)).isEqualTo(jobUpdated);
        assertThat(instant(posting(s, "a").get("last_seen_at"))).isAfterOrEqualTo(lastSeen);
    }

    // ------------------------------------------------------------------ 5. closing and reopening

    @Test
    void missingPostingIsClosedWithItsJob() {
        UUID s = lever("acme", "Acme");
        leverReplies("acme", old("a", "Backend Engineer"), old("b", "Data Engineer"), old("c", "QA Engineer"));
        pollOk(s);
        UUID jobC = jobOf(s, "c");

        leverReplies("acme", old("a", "Backend Engineer"), old("b", "Data Engineer"));
        PollOutcome.Ok ok = pollOk(s);
        assertThat(ok.status()).isEqualTo("OK");
        assertThat(ok.stats().closed()).isEqualTo(1);
        assertThat(ok.stats().jobsClosed()).isEqualTo(1);
        assertThat(posting(s, "c").get("closed_at")).isNotNull();
        assertThat(feedJob(jobC).get("closed_at")).isNotNull();
        assertThat(posting(s, "a").get("closed_at")).isNull();
        assertThat(sourceRow(s).get("open_postings")).isEqualTo(2);
    }

    @Test
    void emptyListingIsSuspiciousFirstAndClosesOnTheNextPoll() {
        UUID s = lever("acme", "Acme");
        leverReplies("acme", old("a", "Backend Engineer"), old("b", "Data Engineer"), old("c", "QA Engineer"));
        pollOk(s);

        STUB.route(leverPath("acme"), Reply.json(200, "[]"));
        PollOutcome.Ok first = pollOk(s);
        assertThat(first.status()).isEqualTo("SUSPICIOUS_EMPTY");
        assertThat(first.stats().closed()).isZero();
        assertThat(sourceRow(s).get("last_status")).isEqualTo("SUSPICIOUS_EMPTY");
        assertThat(sourceRow(s).get("suspicious_since")).isNotNull();
        assertThat(sourceRow(s).get("open_postings")).isEqualTo(3);
        assertThat(posting(s, "a").get("closed_at")).isNull();

        // the provider keeps answering with the very same empty body
        PollOutcome second = pollSync(s);
        assertThat(second).as("second identical empty listing must reach the closing logic, not NOT_MODIFIED "
                + "(§4.5: a second suspicious poll in a row closes)").isInstanceOf(PollOutcome.Ok.class);
        assertThat(((PollOutcome.Ok) second).stats().closed()).isEqualTo(3);
        assertThat(sourceRow(s).get("suspicious_since")).isNull();
        assertThat(sourceRow(s).get("open_postings")).isEqualTo(0);
        assertThat(feedJob(jobOf(s, "a")).get("closed_at")).isNotNull();
    }

    @Test
    void emptyListingClosesOnTheNextPollWhenTheBodyDiffers() {
        UUID s = lever("acme", "Acme");
        leverReplies("acme", old("a", "Backend Engineer"), old("b", "Data Engineer"), old("c", "QA Engineer"));
        pollOk(s);

        STUB.route(leverPath("acme"), Reply.json(200, "[]"));
        assertThat(pollOk(s).status()).isEqualTo("SUSPICIOUS_EMPTY");
        STUB.route(leverPath("acme"), Reply.json(200, "[ ]"));
        PollOutcome.Ok second = pollOk(s);
        assertThat(second.status()).isEqualTo("OK");
        assertThat(second.stats().closed()).isEqualTo(3);
        assertThat(second.stats().jobsClosed()).isEqualTo(3);
        assertThat(sourceRow(s).get("suspicious_since")).isNull();
        assertThat(sourceRow(s).get("open_postings")).isEqualTo(0);
    }

    @Test
    void returningPostingReopensItsJob() {
        UUID s = lever("acme", "Acme");
        LeverJob a = old("a", "Backend Engineer");
        LeverJob b = old("b", "Data Engineer");
        leverReplies("acme", a, b);
        pollOk(s);
        UUID jobA = jobOf(s, "a");
        leverReplies("acme", b);
        pollOk(s);
        assertThat(feedJob(jobA).get("closed_at")).isNotNull();

        leverReplies("acme", a, b);
        PollOutcome.Ok ok = pollOk(s);
        assertThat(ok.stats().reopened()).isEqualTo(1);
        assertThat(posting(s, "a").get("closed_at")).isNull();
        assertThat(jobOf(s, "a")).isEqualTo(jobA);
        assertThat(feedJob(jobA).get("closed_at")).isNull();
        assertThat(feedJob(jobA).get("process_after")).isNotNull();
        assertThat(sourceRow(s).get("open_postings")).isEqualTo(2);
    }

    @Test
    void returningPostingJoinsTheNewerOpenJobWithTheSameKey() {
        UUID s = lever("acme", "Acme");
        LeverJob a = old("a", "Backend Engineer");
        leverReplies("acme", a);
        pollOk(s);
        UUID jobA = jobOf(s, "a");
        STUB.route(leverPath("acme"), Reply.json(200, "[]"));
        pollOk(s);                                                   // 1 open, 0 fetched: closes at once
        assertThat(feedJob(jobA).get("closed_at")).isNotNull();

        // a re-post under a new id: a new open job with the same key
        LeverJob repost = old("a2", "Backend Engineer");
        leverReplies("acme", repost);
        pollOk(s);
        UUID jobRepost = jobOf(s, "a2");
        assertThat(jobRepost).isNotEqualTo(jobA);

        leverReplies("acme", a, repost);
        PollOutcome.Ok ok = pollOk(s);
        assertThat(ok.stats().reopened()).isEqualTo(1);
        assertThat(jobOf(s, "a")).isEqualTo(jobRepost);
        assertThat(feedJob(jobA).get("closed_at")).isNotNull();
        assertThat(feedJob(jobRepost).get("closed_at")).isNull();
    }

    // ------------------------------------------------------------------ 6. dedup across sources

    @Test
    void sameRoleOnGreenhouseAndLeverIsOneFeedJob() {
        UUID gh = greenhouse("acme");
        UUID lv = lever("acmelever", "Acme Inc.");
        Instant t = now().minus(Duration.ofDays(5));
        ghReplies("acme", new GhJob("1001", "Senior Backend Engineer", "Acme", "Remote", t, t,
                "<p>Short GH text.</p>"));
        leverReplies("acmelever", new LeverJob("lv1", "Sr. Backend Engineer - Remote",
                "A much longer Lever description of the very same role, with more detail than Greenhouse.", t));

        pollOk(gh);
        pollOk(lv);
        assertThat(countRows("feed_job")).isEqualTo(1);
        assertThat(countRows("job_posting")).isEqualTo(2);
        UUID jobId = jobOf(gh, "1001");
        assertThat(jobOf(lv, "lv1")).isEqualTo(jobId);
        // both complete ATS descriptions: the longer one is canonical
        assertThat((String) job(jobId).get("description")).startsWith("A much longer Lever description");
        assertThat(countryCodes(jobId)).contains("ZA");
    }

    @Test
    void concurrentPollsOfTwoSourcesWithTheSameKeyGiveOneJob() {
        Instant t = now().minus(Duration.ofDays(5));
        for (int round = 0; round < 3; round++) {
            String title = "Platform Engineer " + round;
            UUID s1 = lever("one" + round, "Acme");
            UUID s2 = lever("two" + round, "ACME Ltd");
            jdbc.update("UPDATE feed_source SET next_poll_at = next_poll_at + interval '1 day' WHERE id NOT IN (?, ?)",
                    s1, s2);
            makeDue(s1);
            makeDue(s2);
            STUB.route(leverPath("one" + round), Reply.json(200, leverBody(new LeverJob("x", title, DESC, t)))
                    .delayed(Duration.ofMillis(300)));
            STUB.route(leverPath("two" + round), Reply.json(200, leverBody(new LeverJob("y", title, DESC, t)))
                    .delayed(Duration.ofMillis(300)));

            assertThat(scheduler.tick()).isEqualTo(2);
            await().atMost(Duration.ofSeconds(20)).until(() -> "OK".equals(sourceRow(s1).get("last_status"))
                    && "OK".equals(sourceRow(s2).get("last_status")));
            assertThat(jobOf(s1, "x")).as("round %d", round).isEqualTo(jobOf(s2, "y"));
        }
        assertThat(countRows("feed_job")).isEqualTo(3);
        assertThat(countRows("job")).as("no orphan job rows left by the losing insert").isEqualTo(3);
    }

    // ------------------------------------------------------------------ 7. not modified

    @Test
    void etag304WritesNothing() {
        UUID s = lever("acme", "Acme");
        String body = leverBody(old("a", "Backend Engineer"));
        STUB.route(leverPath("acme"), req -> "\"v1\"".equals(req.header("If-None-Match"))
                ? new Reply(304, null, null, Duration.ZERO).withHeader("ETag", "\"v1\"")
                : Reply.json(200, body).withHeader("ETag", "\"v1\""));
        pollOk(s);
        assertThat(sourceRow(s).get("etag")).isEqualTo("\"v1\"");
        UUID jobId = jobOf(s, "a");
        Instant jobUpdated = updatedAt("job", jobId);
        Instant feedJobUpdated = instant(feedJob(jobId).get("updated_at"));
        Instant lastSeen = instant(posting(s, "a").get("last_seen_at"));

        PollOutcome o = pollSync(s);
        assertThat(o).isInstanceOf(PollOutcome.NotModified.class);
        assertThat(STUB.requests(leverPath("acme")).get(1).header("If-None-Match")).isEqualTo("\"v1\"");
        assertThat(sourceRow(s).get("last_status")).isEqualTo("NOT_MODIFIED");
        assertThat(sourceRow(s).get("lease_until")).isNull();
        assertThat(instant(posting(s, "a").get("last_seen_at"))).isEqualTo(lastSeen);
        assertThat(updatedAt("job", jobId)).isEqualTo(jobUpdated);
        assertThat(instant(feedJob(jobId).get("updated_at"))).isEqualTo(feedJobUpdated);
    }

    @Test
    void sameBodyHashCountsAsNotModified() {
        UUID s = lever("acme", "Acme");
        leverReplies("acme", old("a", "Backend Engineer"));
        pollOk(s);
        Instant lastSeen = instant(posting(s, "a").get("last_seen_at"));
        PollOutcome o = pollSync(s);
        assertThat(o).isInstanceOf(PollOutcome.NotModified.class);
        assertThat(STUB.requests(leverPath("acme")).get(1).header("If-None-Match")).isNull();
        assertThat(sourceRow(s).get("last_status")).isEqualTo("NOT_MODIFIED");
        assertThat(sourceRow(s).get("last_success_at")).isNotNull();
        assertThat(instant(posting(s, "a").get("last_seen_at"))).isEqualTo(lastSeen);
    }
}
