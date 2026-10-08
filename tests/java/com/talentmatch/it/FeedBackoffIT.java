package com.talentmatch.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.talentmatch.feed.PollOutcome;
import com.talentmatch.feed.source.SourceFailure;
import com.talentmatch.support.AbstractFeedIT;
import com.talentmatch.support.RouteStubServer.Reply;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** §6.1 failures and backoff, §4.4 skipped postings. */
class FeedBackoffIT extends AbstractFeedIT {

    private final Instant t = Instant.now().truncatedTo(ChronoUnit.SECONDS).minus(Duration.ofDays(3));

    private record Window(Instant before, Instant after) {
    }

    private Window pollFailing(UUID s, PollOutcome[] out) {
        Instant before = clock.instant();
        out[0] = pollSync(s);
        return new Window(before, clock.instant());
    }

    private Duration[] nextPollRange(UUID s, Window w) {
        Instant next = instant(sourceRow(s).get("next_poll_at"));
        return new Duration[] {Duration.between(w.after(), next), Duration.between(w.before(), next)};
    }

    /** next_poll_at - poll time within [lo, hi] (allowing for the poll's own duration). */
    private void assertNextIn(UUID s, Window w, Duration lo, Duration hi) {
        Duration[] r = nextPollRange(s, w);
        assertThat(r[1]).as("next poll not earlier than %s", lo).isGreaterThanOrEqualTo(lo.minusMillis(5));
        assertThat(r[0]).as("next poll not later than %s", hi).isLessThanOrEqualTo(hi.plusMillis(5));
    }

    @Test
    void rateLimitedWithRetryAfterSeconds() {
        UUID s = source("LEVER", "acme", "Acme", 120);
        STUB.route(leverPath("acme"), Reply.json(429, "{}").withHeader("Retry-After", "120"));
        PollOutcome[] o = new PollOutcome[1];
        Window w = pollFailing(s, o);
        assertThat(o[0]).isInstanceOf(PollOutcome.Failed.class);
        assertThat(((PollOutcome.Failed) o[0]).failure().kind()).isEqualTo(SourceFailure.Kind.RATE_LIMITED);
        assertThat(sourceRow(s).get("last_status")).isEqualTo("RATE_LIMITED");
        assertThat(sourceRow(s).get("consecutive_failures")).isEqualTo(1);
        assertThat(sourceRow(s).get("lease_until")).isNull();
        assertNextIn(s, w, Duration.ofSeconds(120), Duration.ofSeconds(132));
    }

    @Test
    void rateLimitedWithRetryAfterHttpDate() {
        UUID s = source("LEVER", "acme", "Acme", 120);
        STUB.route(leverPath("acme"), req -> Reply.json(429, "{}")
                .withHeader("Retry-After", httpDate(Instant.now().plusSeconds(300))));
        PollOutcome[] o = new PollOutcome[1];
        Window w = pollFailing(s, o);
        assertThat(sourceRow(s).get("last_status")).isEqualTo("RATE_LIMITED");
        // the date has second precision: 299..300s, plus at most 10%
        assertNextIn(s, w, Duration.ofSeconds(298), Duration.ofSeconds(331));
    }

    @Test
    void repeatedServerErrorsGrowTheBackoff() {
        UUID s = lever("acme", "Acme");                        // interval 300s
        STUB.route(leverPath("acme"), Reply.json(500, "{\"error\":\"boom\"}"));
        long[] base = {600, 1200, 2400, 3600, 3600};
        for (int i = 0; i < base.length; i++) {
            PollOutcome[] o = new PollOutcome[1];
            Window w = pollFailing(s, o);
            assertThat(o[0]).isInstanceOf(PollOutcome.Failed.class);
            assertThat(sourceRow(s).get("consecutive_failures")).isEqualTo(i + 1);
            assertThat(sourceRow(s).get("last_status")).isEqualTo("ERROR");
            assertThat((String) sourceRow(s).get("last_error")).contains("500").doesNotContain("boom");
            assertNextIn(s, w, Duration.ofSeconds(base[i] * 9 / 10), Duration.ofSeconds(base[i] * 11 / 10));
        }
        // a success resets the count
        leverReplies("acme", new LeverJob("a", "Engineer", "desc", t));
        pollOk(s);
        assertThat(sourceRow(s).get("consecutive_failures")).isEqualTo(0);
        assertThat(sourceRow(s).get("last_error")).isNull();
    }

    @Test
    void notFoundWaitsSixHoursAndStaysActive() {
        UUID s = lever("acme", "Acme");
        STUB.route(leverPath("acme"), Reply.text(404, "Not Found"));
        PollOutcome[] o = new PollOutcome[1];
        Window w = pollFailing(s, o);
        assertThat(sourceRow(s).get("last_status")).isEqualTo("NOT_FOUND");
        assertThat(sourceRow(s).get("state")).isEqualTo("ACTIVE");
        assertNextIn(s, w, Duration.ofHours(6).minus(Duration.ofMinutes(36)),
                Duration.ofHours(6).plus(Duration.ofMinutes(36)));
    }

    private List<LeverJob> fiveWithBad(int adapterBad, int normalizerBad) {
        List<LeverJob> jobs = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            LeverJob j = new LeverJob("p" + i, "Engineer " + i, "desc " + i, t);
            if (i < adapterBad) {
                j = j.withUrl(null);                                     // adapter: no http(s) URL
            } else if (i < adapterBad + normalizerBad) {
                j = new LeverJob("x".repeat(201) + i, "Engineer " + i, "desc", t);   // normalizer: id too long
            }
            jobs.add(j);
        }
        return jobs;
    }

    @Test
    void mostlyBadPostingsAreInvalidResponseWithNoWrites() {
        UUID s = lever("acme", "Acme");
        leverReplies("acme", fiveWithBad(1, 2).toArray(LeverJob[]::new));
        PollOutcome o = pollSync(s);
        assertThat(o).isInstanceOf(PollOutcome.Failed.class);
        assertThat(sourceRow(s).get("last_status")).isEqualTo("INVALID_RESPONSE");
        assertThat(sourceRow(s).get("consecutive_failures")).isEqualTo(1);
        assertThat(sourceRow(s).get("baseline_at")).isNull();
        assertThat(postingCount(s)).isZero();
        assertThat(countRows("feed_job")).isZero();
    }

    @Test
    void fewBadPostingsAreSkippedAndTheRestWritten() {
        UUID s = lever("acme", "Acme");
        leverReplies("acme", fiveWithBad(1, 1).toArray(LeverJob[]::new));
        PollOutcome.Ok ok = pollOk(s);
        assertThat(ok.stats().skipped()).isEqualTo(2);
        assertThat(ok.stats().created()).isEqualTo(3);
        assertThat(postingCount(s)).isEqualTo(3);
        assertThat(sourceRow(s).get("last_status")).isEqualTo("OK");
    }

    @Test
    void oneFailingSourceDoesNotAffectAnother() {
        UUID bad = lever("bad", "Bad");
        UUID good = lever("good", "Good");
        makeDue(bad);
        makeDue(good);
        STUB.route(leverPath("bad"), Reply.json(500, "{}"));
        leverReplies("good", new LeverJob("g", "Engineer", "desc", t));
        assertThat(scheduler.tick()).isEqualTo(2);
        await().atMost(Duration.ofSeconds(20)).until(() -> sourceRow(bad).get("last_status") != null
                && sourceRow(good).get("last_status") != null);
        assertThat(sourceRow(bad).get("last_status")).isEqualTo("ERROR");
        assertThat(sourceRow(good).get("last_status")).isEqualTo("OK");
        assertThat(postingCount(good)).isEqualTo(1);
        assertThat(sourceRow(good).get("consecutive_failures")).isEqualTo(0);
    }
}
