package com.talentmatch.web.error;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.net.SocketException;
import java.sql.SQLException;
import java.time.Duration;
import org.hibernate.exception.JDBCConnectionException;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.orm.jpa.JpaSystemException;
import org.springframework.transaction.TransactionSystemException;

/**
 * Timing-independent coverage of the "database unavailable" detection used by the catch-all
 * handler (CI failure: rollback on a dead pooled connection overrode the original error).
 */
class GlobalExceptionHandlerTest {

    /** The exact chain seen in CI: rollback on Hikari's closed-connection proxy, no SQLState. */
    private static RuntimeException ciRollbackChain() {
        SQLException closed = new SQLException("Connection is closed");
        org.hibernate.TransactionException tx =
                new org.hibernate.TransactionException("Unable to rollback against JDBC Connection", closed);
        return new JpaSystemException(tx);
    }

    // ------------------------------------------------------------------ true

    @Test
    void ciChainJpaSystemExceptionOverHikariClosedConnectionIsUnavailable() {
        assertThat(new SQLException("Connection is closed").getSQLState()).isNull();
        assertThat(GlobalExceptionHandler.isDatabaseUnavailable(ciRollbackChain())).isTrue();
    }

    @Test
    void jdbcConnectionExceptionIsUnavailable() {
        PSQLException io = new PSQLException("An I/O error occurred while sending to the backend.",
                PSQLState.CONNECTION_FAILURE, new SocketException("Broken pipe"));
        assertThat(GlobalExceptionHandler.isDatabaseUnavailable(
                new JDBCConnectionException("could not execute statement", io))).isTrue();
        // wrapped further, as Spring's JPA translation would
        assertThat(GlobalExceptionHandler.isDatabaseUnavailable(
                new JpaSystemException(new JDBCConnectionException("could not execute statement", io)))).isTrue();
    }

    @Test
    void psqlException08006IsUnavailable() {
        PSQLException io = new PSQLException("An I/O error occurred", PSQLState.CONNECTION_FAILURE);
        assertThat(io.getSQLState()).isEqualTo("08006");
        assertThat(GlobalExceptionHandler.isDatabaseUnavailable(io)).isTrue();
        assertThat(GlobalExceptionHandler.isDatabaseUnavailable(new RuntimeException("wrapper", io))).isTrue();
    }

    @Test
    void otherConnectionAndShutdownSqlStatesAreUnavailable() {
        for (String state : new String[] {"08001", "08003", "08004", "08P01", "57P01", "57P02", "57P03"}) {
            assertThat(GlobalExceptionHandler.isDatabaseUnavailable(new SQLException("x", state))).as(state).isTrue();
        }
    }

    @Test
    void socketExceptionChainIsUnavailable() {
        assertThat(GlobalExceptionHandler.isDatabaseUnavailable(
                new RuntimeException("outer", new IllegalStateException("mid", new SocketException("Broken pipe")))))
                .isTrue();
        assertThat(GlobalExceptionHandler.isDatabaseUnavailable(
                new RuntimeException(new java.net.ConnectException("Connection refused")))).isTrue();
    }

    @Test
    void transactionSystemExceptionWithConnectionFailureApplicationExceptionIsUnavailable() {
        TransactionSystemException tse = new TransactionSystemException("Could not roll back JPA transaction",
                new IllegalStateException("rollback failed for an unrelated reason"));
        tse.initApplicationException(new JDBCConnectionException("could not execute query",
                new PSQLException("I/O error", PSQLState.CONNECTION_FAILURE)));
        assertThat(GlobalExceptionHandler.isDatabaseUnavailable(tse)).isTrue();
    }

    @Test
    void suppressedAndNextExceptionsAreInspected() {
        RuntimeException withSuppressed = new RuntimeException("primary");
        withSuppressed.addSuppressed(new SQLException("Connection is closed"));
        assertThat(GlobalExceptionHandler.isDatabaseUnavailable(withSuppressed)).isTrue();

        SQLException batch = new SQLException("batch entry failed", "23505");
        batch.setNextException(new SQLException("I/O error", "08006"));
        assertThat(GlobalExceptionHandler.isDatabaseUnavailable(batch)).isTrue();
    }

    // ------------------------------------------------------------------ false

    @Test
    void lockTimeoutIsNotUnavailable() {
        SQLException lock = new SQLException("canceling statement due to lock timeout", "55P03");
        assertThat(GlobalExceptionHandler.isDatabaseUnavailable(lock)).isFalse();
        assertThat(GlobalExceptionHandler.isDatabaseUnavailable(
                new CannotAcquireLockException("could not obtain lock", lock))).isFalse();
        assertThat(GlobalExceptionHandler.isDatabaseUnavailable(
                new JpaSystemException(new org.hibernate.PessimisticLockException("lock", lock, "select")))).isFalse();
    }

    @Test
    void plainRuntimeExceptionIsNotUnavailable() {
        assertThat(GlobalExceptionHandler.isDatabaseUnavailable(new RuntimeException("boom"))).isFalse();
        assertThat(GlobalExceptionHandler.isDatabaseUnavailable(new IllegalStateException("boom",
                new NullPointerException()))).isFalse();
        assertThat(GlobalExceptionHandler.isDatabaseUnavailable(null)).isFalse();
    }

    @Test
    void uniqueViolationIsNotUnavailable() {
        SQLException dup = new SQLException("duplicate key value violates unique constraint", "23505");
        assertThat(GlobalExceptionHandler.isDatabaseUnavailable(dup)).isFalse();
        assertThat(GlobalExceptionHandler.isDatabaseUnavailable(
                new DataIntegrityViolationException("dup", dup))).isFalse();
    }

    @Test
    void sqlExceptionWithoutStateAndOtherMessageIsNotUnavailable() {
        assertThat(GlobalExceptionHandler.isDatabaseUnavailable(new SQLException("Something else"))).isFalse();
        // the Hikari message only counts when there is no SQLState
        assertThat(GlobalExceptionHandler.isDatabaseUnavailable(
                new SQLException("Connection is closed", "23505"))).isFalse();
    }

    @Test
    void cyclicCauseChainTerminates() {
        RuntimeException a = new RuntimeException("a");
        RuntimeException b = new RuntimeException("b", a);
        a.initCause(b); // a -> b -> a
        a.addSuppressed(b);
        b.addSuppressed(a);
        Boolean result = assertTimeoutPreemptively(Duration.ofSeconds(5),
                () -> GlobalExceptionHandler.isDatabaseUnavailable(a));
        assertThat(result).isFalse();

        SQLException s1 = new SQLException("s1", "23505");
        SQLException s2 = new SQLException("s2", "23505");
        s1.setNextException(s2);
        s2.initCause(s1);
        assertThat(assertTimeoutPreemptively(Duration.ofSeconds(5),
                () -> GlobalExceptionHandler.isDatabaseUnavailable(s1))).isFalse();
    }

    @Test
    void cyclicChainStillFindsSignalBehindTheCycle() {
        RuntimeException a = new RuntimeException("a");
        RuntimeException b = new RuntimeException("b", a);
        a.initCause(b);
        b.addSuppressed(new SQLException("Connection is closed"));
        assertThat(assertTimeoutPreemptively(Duration.ofSeconds(5),
                () -> GlobalExceptionHandler.isDatabaseUnavailable(a))).isTrue();
    }

    // ------------------------------------------------------------------ catch-all handler response

    private static MockHttpServletRequest request() {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/jobs");
        req.setAttribute(RequestIdFilter.ATTRIBUTE, "req-123");
        return req;
    }

    @Test
    void catchAllMapsCiChainTo503WithRetryAfter() {
        ResponseEntity<ApiError> res = new GlobalExceptionHandler().unexpected(ciRollbackChain(), request());
        assertThat(res.getStatusCode().value()).isEqualTo(503);
        assertThat(res.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("5");
        ApiError body = res.getBody();
        assertThat(body).isNotNull();
        assertThat(body.status()).isEqualTo(503);
        assertThat(body.code()).isEqualTo("DATABASE_UNAVAILABLE");
        assertThat(body.message()).isEqualTo(GlobalExceptionHandler.DB_UNAVAILABLE_MESSAGE);
        assertThat(body.path()).isEqualTo("/api/jobs");
        assertThat(body.requestId()).isEqualTo("req-123");
    }

    @Test
    void catchAllStillMapsUnrelatedErrorsTo500() {
        ResponseEntity<ApiError> res = new GlobalExceptionHandler().unexpected(
                new IllegalStateException("boom"), request());
        assertThat(res.getStatusCode().value()).isEqualTo(500);
        assertThat(res.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isNull();
        assertThat(res.getBody().code()).isEqualTo("INTERNAL_ERROR");
    }
}
