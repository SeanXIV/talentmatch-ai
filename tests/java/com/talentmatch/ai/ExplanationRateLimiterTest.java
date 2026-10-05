package com.talentmatch.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.talentmatch.service.exception.RegenerateRateLimitedException;
import com.talentmatch.support.MutableClock;
import com.talentmatch.web.error.ErrorCode;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/** Spec §2 ExplanationRateLimiter / §8 message / §10 unit 5. */
class ExplanationRateLimiterTest {

    private final MutableClock clock = MutableClock.fixedAt(AiFixtures.T0);
    private final ExplanationRateLimiter limiter = new ExplanationRateLimiter(AiFixtures.props(), clock);
    private final UUID job = UUID.randomUUID();

    @Test
    void firstAllowedSecondRejectedWithRetryAfterAndMessage() {
        ExplanationRateLimiter.Lease lease = limiter.acquire(job);
        assertThat(lease.jobId()).isEqualTo(job);
        assertThat(lease.grantedAt()).isEqualTo(AiFixtures.T0);

        RegenerateRateLimitedException e = catchThrowableOfType(RegenerateRateLimitedException.class,
                () -> limiter.acquire(job));
        assertThat(e.getRetryAfterSeconds()).isBetween(1, 60).isEqualTo(60);
        assertThat(e.getCode()).isEqualTo(ErrorCode.REGENERATE_RATE_LIMITED);
        assertThat(e.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(e.getMessage()).isEqualTo("Matches for this job were regenerated recently. You can regenerate "
                + "again in 60 seconds; reload without regenerate=true to see the current results.");

        clock.advance(Duration.ofMillis(59_500));
        RegenerateRateLimitedException one = catchThrowableOfType(RegenerateRateLimitedException.class,
                () -> limiter.acquire(job));
        assertThat(one.getRetryAfterSeconds()).as("ceil(0.5s)").isEqualTo(1);
        assertThat(one.getMessage()).contains("again in 1 second;");

        clock.advance(Duration.ofMillis(500));
        assertThat(limiter.acquire(job).grantedAt()).as("allowed at exactly last + window")
                .isEqualTo(AiFixtures.T0.plusSeconds(60));
    }

    @Test
    void ceilOfRemainingSeconds() {
        limiter.acquire(job);
        clock.advance(Duration.ofMillis(1));
        assertThat(catchThrowableOfType(RegenerateRateLimitedException.class, () -> limiter.acquire(job))
                .getRetryAfterSeconds()).isEqualTo(60);
        clock.advance(Duration.ofMillis(1_000));
        assertThat(catchThrowableOfType(RegenerateRateLimitedException.class, () -> limiter.acquire(job))
                .getRetryAfterSeconds()).isEqualTo(59);
    }

    @Test
    void releaseFreesTheSlotOnlyForTheSameLease() {
        ExplanationRateLimiter.Lease first = limiter.acquire(job);
        limiter.release(first);
        clock.advance(Duration.ofSeconds(1));
        ExplanationRateLimiter.Lease second = limiter.acquire(job);
        limiter.release(first); // stale lease: must not free the newer grant
        assertThatThrownBy(() -> limiter.acquire(job)).isInstanceOf(RegenerateRateLimitedException.class);
        limiter.release(second);
        limiter.acquire(job);
        limiter.release(null); // no-op
    }

    @Test
    void jobsAreIndependent() {
        limiter.acquire(job);
        limiter.acquire(UUID.randomUUID());
        assertThatThrownBy(() -> limiter.acquire(job)).isInstanceOf(RegenerateRateLimitedException.class);
    }

    @Test
    void twentyRacingThreadsGetExactlyOneGrant() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(20);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger granted = new AtomicInteger();
        AtomicInteger denied = new AtomicInteger();
        try {
            for (int i = 0; i < 20; i++) {
                pool.submit(() -> {
                    start.await();
                    try {
                        limiter.acquire(job);
                        granted.incrementAndGet();
                    } catch (RegenerateRateLimitedException e) {
                        denied.incrementAndGet();
                    }
                    return null;
                });
            }
            start.countDown();
        } finally {
            pool.shutdown();
            assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(granted.get()).isEqualTo(1);
        assertThat(denied.get()).isEqualTo(19);
    }

    @Test
    void exceptionClampsRetryAfterToAtLeastOne() {
        assertThat(new RegenerateRateLimitedException(0).getRetryAfterSeconds()).isEqualTo(1);
        assertThat(new RegenerateRateLimitedException(-5).getMessage()).contains("in 1 second;");
        assertThat(new RegenerateRateLimitedException(2).getMessage()).contains("in 2 seconds;");
    }
}
