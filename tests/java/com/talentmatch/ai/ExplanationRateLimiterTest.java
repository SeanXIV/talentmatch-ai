package com.talentmatch.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.talentmatch.service.exception.RegenerateRateLimitedException;
import com.talentmatch.support.MutableClock;
import com.talentmatch.web.error.ErrorCode;
import java.time.Duration;
import java.util.List;
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
    // ------------------------------------------------------------------ Phase 6 step 2: scopes

    private static final String CANDIDATE_60 = "Matches for this candidate were regenerated recently. You can "
            + "regenerate again in 60 seconds; reload without regenerate=true to see the current results.";

    @Test
    void jobLeaseCarriesScopeAndOnlyAJobId() {
        ExplanationRateLimiter.Lease lease = limiter.acquire(job);
        assertThat(lease.scope()).isEqualTo(ExplanationRateLimiter.Scope.JOB);
        assertThat(lease.id()).isEqualTo(job);
        assertThat(lease.jobId()).isEqualTo(job);
        assertThat(lease.candidateId()).isNull();
    }

    @Test
    void candidateLeaseCarriesScopeAndOnlyACandidateId() {
        UUID candidate = UUID.randomUUID();
        ExplanationRateLimiter.Lease lease = limiter.acquireForCandidate(candidate);
        assertThat(lease.scope()).isEqualTo(ExplanationRateLimiter.Scope.CANDIDATE);
        assertThat(lease.id()).isEqualTo(candidate);
        assertThat(lease.candidateId()).isEqualTo(candidate);
        assertThat(lease.jobId()).isNull();
        assertThat(lease.grantedAt()).isEqualTo(AiFixtures.T0);
    }

    @Test
    void theSameUuidAsJobAndAsCandidateDoesNotCollide() {
        UUID shared = UUID.randomUUID();
        limiter.acquire(shared);
        assertThat(limiter.acquireForCandidate(shared).candidateId()).as("job slot does not block the candidate")
                .isEqualTo(shared);
        assertThatThrownBy(() -> limiter.acquire(shared)).isInstanceOf(RegenerateRateLimitedException.class)
                .hasMessageStartingWith("Matches for this job ");
        assertThatThrownBy(() -> limiter.acquireForCandidate(shared))
                .isInstanceOf(RegenerateRateLimitedException.class)
                .hasMessageStartingWith("Matches for this candidate ");

        // and the other way round, on a fresh limiter
        ExplanationRateLimiter other = new ExplanationRateLimiter(AiFixtures.props(), clock);
        other.acquireForCandidate(shared);
        assertThat(other.acquire(shared).jobId()).isEqualTo(shared);
    }

    @Test
    void candidatesAreIndependent() {
        UUID a = UUID.randomUUID();
        limiter.acquireForCandidate(a);
        limiter.acquireForCandidate(UUID.randomUUID());
        assertThatThrownBy(() -> limiter.acquireForCandidate(a)).isInstanceOf(RegenerateRateLimitedException.class);
    }

    @Test
    void candidateRejectionHasRetryAfterAndTheCandidateMessage() {
        UUID candidate = UUID.randomUUID();
        limiter.acquireForCandidate(candidate);
        RegenerateRateLimitedException e = catchThrowableOfType(RegenerateRateLimitedException.class,
                () -> limiter.acquireForCandidate(candidate));
        assertThat(e.getRetryAfterSeconds()).isEqualTo(60);
        assertThat(e.getCode()).isEqualTo(ErrorCode.REGENERATE_RATE_LIMITED);
        assertThat(e.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(e.getMessage()).isEqualTo(CANDIDATE_60);

        clock.advance(Duration.ofMillis(59_001));
        RegenerateRateLimitedException one = catchThrowableOfType(RegenerateRateLimitedException.class,
                () -> limiter.acquireForCandidate(candidate));
        assertThat(one.getRetryAfterSeconds()).isEqualTo(1);
        assertThat(one.getMessage()).contains("again in 1 second;");

        clock.advance(Duration.ofMillis(999));
        assertThat(limiter.acquireForCandidate(candidate).grantedAt()).isEqualTo(AiFixtures.T0.plusSeconds(60));
    }

    @Test
    void releaseFreesACandidateSlotOnlyForTheSameLeaseAndOnlyInItsScope() {
        UUID id = UUID.randomUUID();
        ExplanationRateLimiter.Lease jobLease = limiter.acquire(id);
        ExplanationRateLimiter.Lease first = limiter.acquireForCandidate(id);
        limiter.release(first);
        assertThatThrownBy(() -> limiter.acquire(id)).as("releasing the candidate lease keeps the job slot")
                .isInstanceOf(RegenerateRateLimitedException.class);

        clock.advance(Duration.ofSeconds(1));
        ExplanationRateLimiter.Lease second = limiter.acquireForCandidate(id);
        limiter.release(first); // stale lease
        assertThatThrownBy(() -> limiter.acquireForCandidate(id)).isInstanceOf(RegenerateRateLimitedException.class);

        limiter.release(jobLease); // other scope with the same id
        assertThatThrownBy(() -> limiter.acquireForCandidate(id)).as("job release keeps the candidate slot")
                .isInstanceOf(RegenerateRateLimitedException.class);
        assertThat(limiter.acquire(id).jobId()).isEqualTo(id);

        limiter.release(second);
        assertThat(limiter.acquireForCandidate(id).candidateId()).isEqualTo(id);
    }

    @Test
    void forgedLeaseOfTheWrongScopeDoesNotFreeTheSlot() {
        UUID id = UUID.randomUUID();
        ExplanationRateLimiter.Lease real = limiter.acquireForCandidate(id);
        limiter.release(new ExplanationRateLimiter.Lease(ExplanationRateLimiter.Scope.JOB, id, real.grantedAt()));
        assertThatThrownBy(() -> limiter.acquireForCandidate(id)).isInstanceOf(RegenerateRateLimitedException.class);
    }

    @Test
    void leaseAndAcquireRejectNulls() {
        assertThatThrownBy(() -> limiter.acquireForCandidate(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> limiter.acquire(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ExplanationRateLimiter.Lease(null, job, AiFixtures.T0))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ExplanationRateLimiter.Lease(ExplanationRateLimiter.Scope.JOB, null,
                AiFixtures.T0)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ExplanationRateLimiter.Lease(ExplanationRateLimiter.Scope.JOB, job, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void twentyRacingThreadsPerScopeGetExactlyOneGrantEach() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(20);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger jobGrants = new AtomicInteger();
        AtomicInteger candidateGrants = new AtomicInteger();
        try {
            for (int i = 0; i < 20; i++) {
                boolean asCandidate = i % 2 == 0;
                pool.submit(() -> {
                    start.await();
                    try {
                        if (asCandidate) {
                            limiter.acquireForCandidate(job);
                            candidateGrants.incrementAndGet();
                        } else {
                            limiter.acquire(job);
                            jobGrants.incrementAndGet();
                        }
                    } catch (RegenerateRateLimitedException e) {
                        // denied
                    }
                    return null;
                });
            }
            start.countDown();
        } finally {
            pool.shutdown();
            assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(jobGrants.get()).isEqualTo(1);
        assertThat(candidateGrants.get()).isEqualTo(1);
    }

    @Test
    void factoriesBuildTheJobAndCandidateMessages() {
        assertThat(RegenerateRateLimitedException.forJob(60).getMessage())
                .isEqualTo(new RegenerateRateLimitedException(60).getMessage())
                .isEqualTo("Matches for this job were regenerated recently. You can regenerate again in 60 seconds; "
                        + "reload without regenerate=true to see the current results.");
        assertThat(RegenerateRateLimitedException.forCandidate(60).getMessage()).isEqualTo(CANDIDATE_60);
        for (RegenerateRateLimitedException e : List.of(RegenerateRateLimitedException.forJob(0),
                RegenerateRateLimitedException.forCandidate(-3))) {
            assertThat(e.getRetryAfterSeconds()).isEqualTo(1);
            assertThat(e.getMessage()).contains("in 1 second;");
            assertThat(e.getCode()).isEqualTo(ErrorCode.REGENERATE_RATE_LIMITED);
            assertThat(e.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        }
        assertThat(RegenerateRateLimitedException.forCandidate(7).getRetryAfterSeconds()).isEqualTo(7);
    }
}
