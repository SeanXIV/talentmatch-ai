package com.talentmatch.notify;

import java.sql.SQLException;
import org.springframework.dao.PessimisticLockingFailureException;

/**
 * Exception helpers for {@code notify} logs. Small local copies of the feed package's helpers
 * ({@code SourcePoller.describe}, {@code LockRetry.isLockConflict}), because {@code notify} never imports
 * {@code feed} (D14).
 */
final class NotifyErrors {

    private NotifyErrors() {
    }

    /** Exception classes and SQLStates only: database messages can quote row data, mail messages addresses. */
    static String describe(Throwable e) {
        StringBuilder sb = new StringBuilder();
        int depth = 0;
        for (Throwable t = e; t != null && depth < 8; t = t.getCause() == t ? null : t.getCause(), depth++) {
            if (sb.length() > 0) {
                sb.append(" <- ");
            }
            sb.append(t.getClass().getName());
            if (t instanceof SQLException sql && sql.getSQLState() != null) {
                sb.append("[SQLState ").append(sql.getSQLState()).append(']');
            }
        }
        return sb.toString();
    }

    /** A lock conflict: Spring's pessimistic-locking family, or SQLState 40P01 / 55P03. */
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
