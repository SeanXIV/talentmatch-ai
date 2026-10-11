package com.talentmatch.feed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.SQLException;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.jdbc.UncategorizedSQLException;

/** Bounded retry around the refresh markers; DATABASE_ORDER (unsigned, PostgreSQL uuid order). */
class LockRetryTest {

    private static RuntimeException deadlock() {
        return new UncategorizedSQLException("update", "UPDATE", new SQLException("deadlock detected", "40P01"));
    }

    @Test
    void lockConflictsAreRecognised() {
        assertThat(LockRetry.isLockConflict(new CannotAcquireLockException("x"))).isTrue();
        assertThat(LockRetry.isLockConflict(new PessimisticLockingFailureException("x"))).isTrue();
        assertThat(LockRetry.isLockConflict(deadlock())).isTrue();
        assertThat(LockRetry.isLockConflict(new RuntimeException(new SQLException("timeout", "55P03")))).isTrue();
        assertThat(LockRetry.isLockConflict(new RuntimeException(new SQLException("unique", "23505")))).isFalse();
        assertThat(LockRetry.isLockConflict(new DataIntegrityViolationException("x"))).isFalse();
        assertThat(LockRetry.isLockConflict(new IllegalStateException("x"))).isFalse();
        assertThat(LockRetry.isLockConflict(null)).isFalse();
        RuntimeException self = new RuntimeException("loop") {
            @Override
            public synchronized Throwable getCause() {
                return this;
            }
        };
        assertThat(LockRetry.isLockConflict(self)).isFalse();
    }

    @Test
    void retriesALockConflictUpToThreeAttempts() {
        AtomicInteger calls = new AtomicInteger();
        assertThatThrownBy(() -> LockRetry.run("test", () -> {
            calls.incrementAndGet();
            throw deadlock();
        })).isInstanceOf(UncategorizedSQLException.class);
        assertThat(calls).hasValue(LockRetry.ATTEMPTS).hasValue(3);
    }

    @Test
    void succeedsOnALaterAttempt() {
        AtomicInteger calls = new AtomicInteger();
        String result = LockRetry.run("test", () -> {
            if (calls.incrementAndGet() < 3) {
                throw new CannotAcquireLockException("busy");
            }
            return "ok";
        });
        assertThat(result).isEqualTo("ok");
        assertThat(calls).hasValue(3);
    }

    @Test
    void otherExceptionsAreNotRetried() {
        AtomicInteger calls = new AtomicInteger();
        assertThatThrownBy(() -> LockRetry.run("test", () -> {
            calls.incrementAndGet();
            throw new DataIntegrityViolationException("dup");
        })).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(calls).hasValue(1);
        assertThat(LockRetry.run("test", calls::incrementAndGet)).isEqualTo(2);
    }

    @Test
    void interruptStopsRetrying() {
        AtomicInteger calls = new AtomicInteger();
        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> LockRetry.run("test", () -> {
                calls.incrementAndGet();
                throw deadlock();
            })).isInstanceOf(UncategorizedSQLException.class);
            assertThat(calls).hasValue(1);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void databaseOrderIsUnsigned() {
        UUID low = UUID.fromString("00000000-0000-0000-0000-000000000000");
        UUID mid = UUID.fromString("7fffffff-ffff-ffff-ffff-ffffffffffff");
        UUID high = UUID.fromString("80000000-0000-0000-0000-000000000000");
        UUID top = UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff");
        assertThat(high.compareTo(mid)).as("UUID.compareTo is signed").isNegative();
        assertThat(FeedJobRepository.DATABASE_ORDER.compare(high, mid)).isPositive();
        UUID lsbLow = UUID.fromString("12345678-0000-0000-7fff-ffffffffffff");
        UUID lsbHigh = UUID.fromString("12345678-0000-0000-8000-000000000000");
        assertThat(FeedJobRepository.DATABASE_ORDER.compare(lsbHigh, lsbLow)).isPositive();
        assertThat(FeedJobRepository.sortedForDatabase(java.util.List.of(top, high, low, mid, high, lsbHigh, lsbLow)))
                .containsExactly(low, lsbLow, lsbHigh, mid, high, top);
    }
}
