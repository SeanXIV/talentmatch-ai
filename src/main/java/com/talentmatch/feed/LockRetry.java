package com.talentmatch.feed;

import java.sql.SQLException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.PessimisticLockingFailureException;

/**
 * A small bounded retry for short transactions that can lose a lock conflict (a deadlock, 40P01, or
 * a lock timeout, 55P03) against a concurrent poll or processor run: the open-job markers of
 * {@link FeedRefreshService}. Each attempt must be a whole transaction of its own (the loser's
 * transaction is rolled back by PostgreSQL), so the action passed in starts and ends one.
 */
final class LockRetry {

    private static final Logger log = LoggerFactory.getLogger(LockRetry.class);

    /** Attempts in total (the first try plus two retries). */
    static final int ATTEMPTS = 3;
    private static final long BASE_SLEEP_MS = 25;
    private static final long JITTER_MS = 75;

    private LockRetry() {
    }

    /**
     * Runs {@code action}; on a lock conflict waits a short jittered time and runs it again, at most
     * {@link #ATTEMPTS} times in total. Anything else, or the last conflict, is rethrown.
     */
    static <T> T run(String what, Supplier<T> action) {
        for (int attempt = 1; ; attempt++) {
            try {
                return action.get();
            } catch (RuntimeException e) {
                if (attempt >= ATTEMPTS || !isLockConflict(e)) {
                    throw e;
                }
                long sleep = BASE_SLEEP_MS * attempt + ThreadLocalRandom.current().nextLong(JITTER_MS + 1);
                log.debug("{}: lock conflict on attempt {} ({}); retrying in {} ms", what, attempt,
                        SourcePoller.describe(e), sleep);
                try {
                    Thread.sleep(sleep);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
    }

    /**
     * True when {@code e} (or a cause) is a lock conflict: Spring's
     * {@link PessimisticLockingFailureException} family, or SQLState 40P01 (deadlock detected) /
     * 55P03 (lock not available) on an untranslated {@link SQLException}.
     */
    static boolean isLockConflict(Throwable e) {
        int depth = 0;
        for (Throwable t = e; t != null && depth < 16; t = t.getCause() == t ? null : t.getCause(), depth++) {
            if (t instanceof PessimisticLockingFailureException) {
                return true;
            }
            if (t instanceof SQLException sql && ("40P01".equals(sql.getSQLState()) || "55P03".equals(sql.getSQLState()))) {
                return true;
            }
        }
        return false;
    }
}
