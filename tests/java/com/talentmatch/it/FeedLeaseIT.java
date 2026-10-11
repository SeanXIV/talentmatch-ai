package com.talentmatch.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.talentmatch.feed.FeedScheduler;
import com.talentmatch.feed.FeedSource;
import com.talentmatch.feed.PollOutcome;
import com.talentmatch.feed.source.SourceProperties;
import com.talentmatch.support.AbstractFeedIT;
import com.talentmatch.support.RouteStubServer.Reply;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadPoolExecutor;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/** §4.1 claiming, leases, poll threads and startup recovery. */
class FeedLeaseIT extends AbstractFeedIT {

    private final Instant t = Instant.now().truncatedTo(ChronoUnit.SECONDS).minus(Duration.ofDays(3));

    private Object lease(UUID id) {
        return sourceRow(id).get("lease_until");
    }

    @Test
    void leasedSourceIsNotClaimedAgain() {
        UUID s = lever("acme", "Acme");
        makeDue(s);
        Instant now = clock.instant();
        assertThat(sources.claim(s, now, scheduler.leaseUntil(now))).isPresent();
        assertThat(lease(s)).isNotNull();
        assertThat(sources.claim(s, now, scheduler.leaseUntil(now))).isEmpty();
        assertThat(sources.claimDue(10, now, scheduler.leaseUntil(now))).isEmpty();
        assertThat(scheduler.tick()).isZero();
    }

    @Test
    void expiredLeaseCanBeClaimed() {
        UUID s = lever("acme", "Acme");
        makeDue(s);
        jdbc.update("UPDATE feed_source SET lease_until = ? WHERE id = ?",
                Timestamp.from(clock.instant().minusSeconds(1)), s);
        Instant now = clock.instant();
        assertThat(sources.claimDue(10, now, scheduler.leaseUntil(now))).extracting(FeedSource::id).containsExactly(s);
    }

    @Test
    void notDueSourceIsNotClaimed() {
        UUID s = lever("acme", "Acme");
        makeNotDue(s);
        Instant now = clock.instant();
        assertThat(sources.claimDue(10, now, scheduler.leaseUntil(now))).isEmpty();
    }

    @Test
    void lostLeaseMidPollWritesNothing() throws Exception {
        UUID s = lever("acme", "Acme");
        STUB.route(leverPath("acme"), Reply.json(200, leverBody(new LeverJob("a", "Engineer", "desc", t)))
                .delayed(Duration.ofMillis(1500)));
        CompletableFuture<PollOutcome> running = CompletableFuture.supplyAsync(() -> pollSync(s));
        await().atMost(Duration.ofSeconds(10)).until(() -> !STUB.requests(leverPath("acme")).isEmpty());
        // another poller took the source over
        jdbc.update("UPDATE feed_source SET lease_until = ? WHERE id = ?",
                Timestamp.from(clock.instant().plus(Duration.ofMinutes(10))), s);

        PollOutcome outcome = running.get();
        assertThat(outcome).isInstanceOf(PollOutcome.LeaseLost.class);
        assertThat(postingCount(s)).isZero();
        assertThat(countRows("feed_job")).isZero();
        assertThat(sourceRow(s).get("last_status")).isNull();
        assertThat(sourceRow(s).get("last_polled_at")).isNull();
    }

    @Test
    void lostLeaseOnAFailedPollRecordsNothing() throws Exception {
        UUID s = lever("acme", "Acme");
        STUB.route(leverPath("acme"), Reply.json(500, "{}").delayed(Duration.ofMillis(1500)));
        CompletableFuture<PollOutcome> running = CompletableFuture.supplyAsync(() -> pollSync(s));
        await().atMost(Duration.ofSeconds(10)).until(() -> !STUB.requests(leverPath("acme")).isEmpty());
        jdbc.update("UPDATE feed_source SET lease_until = ? WHERE id = ?",
                Timestamp.from(clock.instant().plus(Duration.ofMinutes(10))), s);
        assertThat(running.get()).isInstanceOf(PollOutcome.LeaseLost.class);
        assertThat(sourceRow(s).get("consecutive_failures")).isEqualTo(0);
    }

    @Test
    void withOnePollThreadTheSecondDueSourceWaits() {
        SourceProperties sp = sourceProperties;
        SourceProperties.Http h = sp.http();
        SourceProperties oneThread = new SourceProperties(sp.maxPostingsPerSource(),
                new SourceProperties.Http(h.connectTimeout(), h.readTimeout(), h.maxBodyBytes(), 1,
                        h.minHostSpacing(), h.userAgent()), sp.greenhouse(), sp.lever(), sp.ashby());
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(0);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.initialize();
        try {
            FeedScheduler single = new FeedScheduler(sources, poller, executor, feedProperties, oneThread, clock);
            UUID a = lever("a", "A");
            UUID b = lever("b", "B");
            makeDue(a);
            makeDue(b);
            STUB.route(leverPath("a"), Reply.json(200, leverBody(new LeverJob("x", "Engineer", "d", t)))
                    .delayed(Duration.ofMillis(1500)));
            STUB.route(leverPath("b"), Reply.json(200, leverBody(new LeverJob("y", "Engineer", "d", t)))
                    .delayed(Duration.ofMillis(1500)));

            assertThat(single.tick()).isEqualTo(1);
            List<Object> leases = List.of(String.valueOf(lease(a)), String.valueOf(lease(b)));
            assertThat(leases).as("exactly one source leased").containsOnlyOnce("null");
            await().atMost(Duration.ofSeconds(5)).until(() -> executor.getActiveCount() == 1);
            assertThat(single.tick()).as("no free thread").isZero();

            // a source claimed while the thread is busy is given back and stays due
            UUID c = lever("c", "C");
            Instant now = clock.instant();
            FeedSource claimed = sources.claim(c, now, single.leaseUntil(now)).orElseThrow();
            assertThat(single.submit(claimed)).isFalse();
            assertThat(lease(c)).isNull();
            assertThat(instant(sourceRow(c).get("next_poll_at"))).isBeforeOrEqualTo(clock.instant());
            makeNotDue(c);

            await().atMost(Duration.ofSeconds(20)).until(() -> executor.getActiveCount() == 0
                    && (sourceRow(a).get("last_status") != null || sourceRow(b).get("last_status") != null));
            assertThat(single.tick()).isEqualTo(1);
            await().atMost(Duration.ofSeconds(20)).until(() -> "OK".equals(sourceRow(a).get("last_status"))
                    && "OK".equals(sourceRow(b).get("last_status")));
        } finally {
            executor.shutdown();
        }
    }

    @Test
    void recoveryDropsStaleLeases() {
        UUID a = lever("a", "A");
        UUID b = lever("b", "B");
        jdbc.update("UPDATE feed_source SET lease_until = ?",
                Timestamp.from(clock.instant().plus(Duration.ofMinutes(5))));
        recovery.recover();
        assertThat(lease(a)).isNull();
        assertThat(lease(b)).isNull();
        makeDue(a);
        Instant now = clock.instant();
        assertThat(sources.claimDue(10, now, scheduler.leaseUntil(now))).extracting(FeedSource::id).contains(a);
    }

    @Test
    void pausedSourceIsNeverClaimed() {
        UUID s = lever("acme", "Acme");
        jdbc.update("UPDATE feed_source SET state = 'PAUSED' WHERE id = ?", s);
        makeDue(s);
        Instant now = clock.instant();
        assertThat(sources.claimDue(10, now, scheduler.leaseUntil(now))).isEmpty();
        assertThat(scheduler.tick()).isZero();
        assertThat(STUB.requests()).isEmpty();
    }
}
