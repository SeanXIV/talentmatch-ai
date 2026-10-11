package com.talentmatch.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import com.talentmatch.feed.FeedConfig;
import com.talentmatch.support.AbstractFeedIT;
import com.talentmatch.support.Api.Res;
import com.talentmatch.support.RouteStubServer.Reply;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

/** §5.1 {@code POST /api/feed/sources/{id}/poll}, §5.2 {@code GET /api/feed/status}, §6.5 health. */
class FeedPollEndpointIT extends AbstractFeedIT {

    private final Instant t = Instant.now().truncatedTo(ChronoUnit.SECONDS).minus(Duration.ofDays(3));

    @Autowired
    private ApplicationContext context;

    private Res poll(Object id) {
        return api.post("/api/feed/sources/" + id + "/poll", null);
    }

    private void awaitStatus(UUID id, String status) {
        await().atMost(Duration.ofSeconds(20)).until(() -> status.equals(sourceRow(id).get("last_status")));
        awaitPollThreadsIdle();
    }

    // ------------------------------------------------------------------ poll endpoint

    @Test
    void pollReturns202AndPollsInTheBackground() {
        UUID s = lever("acme", "Acme");
        leverReplies("acme", new LeverJob("a", "Engineer", "desc", t));
        Res r = poll(s);
        assertThat(r.status()).as("%s", r).isEqualTo(202);
        JsonNode b = r.json();
        assertThat(b.get("sourceId").asText()).isEqualTo(s.toString());
        assertThat(b.get("queued").asBoolean()).isTrue();
        assertThat(b.size()).isEqualTo(2);
        awaitStatus(s, "OK");
        Res get = api.get("/api/feed/sources/" + s);
        assertThat(get.json().get("lastStatus").asText()).isEqualTo("OK");
        assertThat(get.json().get("openPostings").asInt()).isEqualTo(1);
        assertThat(get.json().get("baselineAt").isNull()).isFalse();
    }

    @Test
    void pausedSourceCanBePolledOnRequest() {
        UUID s = lever("acme", "Acme");
        jdbc.update("UPDATE feed_source SET state = 'PAUSED' WHERE id = ?", s);
        leverReplies("acme", new LeverJob("a", "Engineer", "desc", t));
        assertThat(poll(s).status()).isEqualTo(202);
        awaitStatus(s, "OK");
        assertThat(sourceRow(s).get("state")).isEqualTo("PAUSED");
    }

    @Test
    void unknownAndInvalidIds() {
        assertError(poll(UUID.randomUUID()), 404, "FEED_SOURCE_NOT_FOUND");
        assertError(poll("not-a-uuid"), 400, "INVALID_ID");
    }

    @Test
    void heldLeaseIs409InProgress() {
        UUID s = lever("acme", "Acme");
        jdbc.update("UPDATE feed_source SET lease_until = ? WHERE id = ?",
                Timestamp.from(clock.instant().plus(Duration.ofMinutes(4))), s);
        assertError(poll(s), 409, "FEED_POLL_IN_PROGRESS");
        assertThat(STUB.requests()).isEmpty();
        // an expired lease doesn't block
        jdbc.update("UPDATE feed_source SET lease_until = ? WHERE id = ?",
                Timestamp.from(clock.instant().minusSeconds(1)), s);
        leverReplies("acme", new LeverJob("a", "Engineer", "desc", t));
        assertThat(poll(s).status()).isEqualTo(202);
        awaitStatus(s, "OK");
    }

    @Test
    void pollWithinAMinuteIs429WithRetryAfter() {
        UUID s = lever("acme", "Acme");
        leverReplies("acme", new LeverJob("a", "Engineer", "desc", t));
        assertThat(poll(s).status()).isEqualTo(202);
        awaitStatus(s, "OK");

        Res r = poll(s);
        JsonNode err = assertError(r, 429, "FEED_POLL_RATE_LIMITED");
        int retryAfter = Integer.parseInt(r.header("Retry-After"));
        assertThat(retryAfter).isBetween(1, 60);
        assertThat(err.get("message").asText()).contains("less than a minute ago").contains(retryAfter + " second");

        jdbc.update("UPDATE feed_source SET last_polled_at = ? WHERE id = ?",
                Timestamp.from(clock.instant().minusSeconds(61)), s);
        assertThat(poll(s).status()).isEqualTo(202);
        awaitPollThreadsIdle();
    }

    @Test
    void busyPollThreadsStill202AndMakeTheSourceDue() {
        UUID a = lever("a", "A");
        UUID b = lever("b", "B");
        UUID c = lever("c", "C");
        makeDue(a);
        makeDue(b);
        makeNotDue(c);
        STUB.route(leverPath("a"), Reply.json(200, leverBody(new LeverJob("x", "E", "d", t))).delayed(Duration.ofSeconds(2)));
        STUB.route(leverPath("b"), Reply.json(200, leverBody(new LeverJob("y", "E", "d", t))).delayed(Duration.ofSeconds(2)));
        assertThat(scheduler.tick()).isEqualTo(2);
        await().atMost(Duration.ofSeconds(5)).until(() -> pollExecutor.getActiveCount() == 2);

        Res r = poll(c);
        assertThat(r.status()).isEqualTo(202);
        assertThat(r.json().get("queued").asBoolean()).isTrue();
        assertThat(sourceRow(c).get("lease_until")).isNull();
        assertThat(instant(sourceRow(c).get("next_poll_at"))).isBeforeOrEqualTo(clock.instant());
        awaitPollThreadsIdle();
    }

    // ------------------------------------------------------------------ status

    @Test
    void statusCountsSourcesAndPendingProcessing() {
        Res empty = api.get("/api/feed/status");
        assertThat(empty.status()).isEqualTo(200);
        assertThat(empty.json().get("sources").get("total").asLong()).isZero();
        assertThat(empty.json().get("sources").get("lastSuccessAt").isNull()).isTrue();
        assertThat(empty.json().get("processing").get("pending").asLong()).isZero();

        UUID ok = lever("ok", "Ok");
        UUID failing = lever("failing", "Failing");
        UUID paused = lever("paused", "Paused");
        leverReplies("ok", new LeverJob("a", "Engineer", "d", t), new LeverJob("b", "Designer", "d", t));
        STUB.route(leverPath("failing"), Reply.json(500, "{}"));
        pollOk(ok);
        pollSync(failing);
        jdbc.update("UPDATE feed_source SET state = 'PAUSED', consecutive_failures = 5 WHERE id = ?", paused);

        JsonNode s = api.get("/api/feed/status").json();
        assertThat(s.get("enabled").asBoolean()).isTrue();
        assertThat(s.get("schedulerEnabled").asBoolean()).as("scheduler off in tests").isFalse();
        assertThat(s.get("sources").get("total").asLong()).isEqualTo(3);
        assertThat(s.get("sources").get("active").asLong()).isEqualTo(2);
        assertThat(s.get("sources").get("failing").asLong()).as("paused sources don't count").isEqualTo(1);
        assertThat(Instant.parse(s.get("sources").get("lastSuccessAt").asText())).isNotNull();
        assertThat(s.get("processing").get("pending").asLong()).isEqualTo(2);

        jdbc.update("UPDATE feed_job SET process_after = NULL");
        assertThat(api.get("/api/feed/status").json().get("processing").get("pending").asLong()).isZero();
    }

    // ------------------------------------------------------------------ health

    private JsonNode health() {
        Res r = api.get("/actuator/health");
        assertThat(r.status()).as("overall health %s", r).isEqualTo(200);
        assertThat(r.json().get("status").asText()).isEqualTo("UP");
        return r.json().get("components").get("feed");
    }

    @Test
    void healthDegradedAfterThreeFailuresButOverallUp() {
        UUID s = lever("acme", "Acme");
        STUB.route(leverPath("acme"), Reply.json(500, "{}"));
        pollSync(s);
        pollSync(s);
        assertThat(health().get("status").asText()).isEqualTo("UP");
        pollSync(s);
        JsonNode feed = health();
        assertThat(feed.get("status").asText()).isEqualTo("DEGRADED");
        assertThat(feed.get("details").get("reasons").toString()).contains("SOURCES_FAILING");
        assertThat(feed.get("details").get("failingSourceIds").get(0).asText()).isEqualTo(s.toString());
        assertThat(feed.get("details").toString()).doesNotContain("localhost").doesNotContain("127.0.0.1");

        jdbc.update("UPDATE feed_source SET state = 'PAUSED' WHERE id = ?", s);
        assertThat(health().get("status").asText()).as("paused sources are ignored").isEqualTo("UP");
    }

    @Test
    void healthDegradedForAStaleSource() {
        UUID s = lever("acme", "Acme");                       // interval 300s: stale after 15 min
        jdbc.update("UPDATE feed_source SET last_polled_at = ?, last_success_at = ? WHERE id = ?",
                Timestamp.from(clock.instant()), Timestamp.from(clock.instant().minus(Duration.ofMinutes(16))), s);
        JsonNode feed = health();
        assertThat(feed.get("status").asText()).isEqualTo("DEGRADED");
        assertThat(feed.get("details").get("reasons").toString()).contains("SOURCES_STALE");

        jdbc.update("UPDATE feed_source SET last_success_at = ? WHERE id = ?",
                Timestamp.from(clock.instant().minus(Duration.ofMinutes(14))), s);
        assertThat(health().get("status").asText()).isEqualTo("UP");
    }

    @Test
    void healthUpWithNoSourcesAndNeverPolledSourceIsNotStale() {
        assertThat(health().get("status").asText()).isEqualTo("UP");
        UUID s = lever("acme", "Acme");
        jdbc.update("UPDATE feed_source SET created_at = ? WHERE id = ?",
                Timestamp.from(clock.instant().minus(Duration.ofDays(2))), s);
        JsonNode feed = health();
        assertThat(feed.get("status").asText()).isEqualTo("UP");
        assertThat(feed.get("details").get("enabled").asBoolean()).isTrue();
        assertThat(feed.get("details").get("activeSources").asInt()).isEqualTo(1);
    }

    // ------------------------------------------------------------------ wiring

    @Test
    void noSchedulingBeanWithTheSchedulerOff() {
        assertThat(context.getBeanNamesForType(
                org.springframework.scheduling.annotation.SchedulingConfigurer.class))
                .noneMatch(n -> n.toLowerCase().contains("feedscheduling"));
        assertThat(context.getBean(FeedConfig.POLL_EXECUTOR)).isNotNull();
    }
}
