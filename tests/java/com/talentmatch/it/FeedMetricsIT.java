package com.talentmatch.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.talentmatch.feed.FeedMetrics;
import com.talentmatch.feed.PollOutcome;
import com.talentmatch.support.AbstractFeedIT;
import com.talentmatch.support.RouteStubServer.Reply;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Step 11 poll meters against the stub server: {@code talentmatch.feed.polls} per outcome and
 * {@code talentmatch.feed.postings.new} without the baseline poll. Meters are cumulative across the
 * shared context, so every assertion is a delta.
 */
class FeedMetricsIT extends AbstractFeedIT {

    private static final String DESC = "We build payment rails. You will own Java services and PostgreSQL.";

    @Autowired
    private MeterRegistry meters;

    private final Instant start = Instant.now().truncatedTo(ChronoUnit.SECONDS);

    private LeverJob old(String id) {
        return new LeverJob(id, "Engineer " + id, DESC + " " + id, start.minus(Duration.ofDays(10)));
    }

    private long polls(String kind, String outcome) {
        Timer t = meters.find(FeedMetrics.POLLS).tags("kind", kind, "outcome", outcome).timer();
        return t == null ? 0 : t.count();
    }

    private double newPostings(String kind) {
        Counter c = meters.find(FeedMetrics.NEW_POSTINGS).tag("kind", kind).counter();
        return c == null ? 0 : c.count();
    }

    @Test
    void baselinePollIsTimedButItsPostingsAreNotNew() {
        UUID s = lever("acme", "Acme");
        leverReplies("acme", old("a"), old("b"));
        long okBefore = polls("lever", "ok");
        double newBefore = newPostings("lever");

        PollOutcome.Ok ok = pollOk(s);
        assertThat(ok.stats().created()).isEqualTo(2);
        assertThat(polls("lever", "ok") - okBefore).isEqualTo(1);
        assertThat(newPostings("lever") - newBefore).as("baseline poll not counted").isZero();

        // the next poll finds one new posting
        leverReplies("acme", old("a"), old("b"), old("n"));
        assertThat(pollOk(s).stats().created()).isEqualTo(1);
        assertThat(polls("lever", "ok") - okBefore).isEqualTo(2);
        assertThat(newPostings("lever") - newBefore).isEqualTo(1);

        // a changed posting, nothing new: the counter stays
        leverReplies("acme", old("a").withDescription("A changed description."), old("b"), old("n"));
        PollOutcome.Ok changed = pollOk(s);
        assertThat(changed.stats().created()).isZero();
        assertThat(changed.stats().updated()).isEqualTo(1);
        assertThat(newPostings("lever") - newBefore).isEqualTo(1);
        assertThat(polls("lever", "ok") - okBefore).isEqualTo(3);
        assertThat(meters.find(FeedMetrics.POLLS).tag("kind", "lever").timers()).allSatisfy(t ->
                assertThat(t.getId().getTag("outcome")).isLowerCase());
    }

    @Test
    void notModifiedIsTaggedNotModified() {
        UUID s = lever("acme", "Acme");
        String body = leverBody(old("a"));
        STUB.route(leverPath("acme"), req -> "\"v1\"".equals(req.header("If-None-Match"))
                ? new Reply(304, null, null, Duration.ZERO).withHeader("ETag", "\"v1\"")
                : Reply.json(200, body).withHeader("ETag", "\"v1\""));
        pollOk(s);
        long before = polls("lever", "not_modified");
        assertThat(pollSync(s)).isInstanceOf(PollOutcome.NotModified.class);
        assertThat(polls("lever", "not_modified") - before).isEqualTo(1);
    }

    @Test
    void aServerErrorIsTaggedError() {
        UUID s = lever("acme", "Acme");
        STUB.route(leverPath("acme"), Reply.json(500, "{\"error\":\"boom\"}"));
        long before = polls("lever", "error");
        long okBefore = polls("lever", "ok");
        double newBefore = newPostings("lever");
        assertThat(pollSync(s)).isInstanceOf(PollOutcome.Failed.class);
        assertThat(polls("lever", "error") - before).isEqualTo(1);
        assertThat(polls("lever", "ok")).isEqualTo(okBefore);
        assertThat(newPostings("lever")).isEqualTo(newBefore);
    }

    @Test
    void notFoundIsTaggedNotFoundAndGreenhouseHasItsOwnKind() {
        UUID s = greenhouse("nosuch");
        STUB.route(ghListPath("nosuch"), Reply.json(404, "{}"));
        long before = polls("greenhouse", "not_found");
        assertThat(pollSync(s)).isInstanceOf(PollOutcome.Failed.class);
        assertThat(polls("greenhouse", "not_found") - before).isEqualTo(1);
    }
}
