package com.talentmatch.ai;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Per-instance circuit breaker around the model provider.
 *
 * <p>CLOSED until {@code failureThreshold} consecutive provider failures (timeouts / errors),
 * then OPEN for {@code openDuration}, then HALF_OPEN: exactly one probe is allowed; its success
 * closes the circuit and its failure reopens it. Refusals and invalid output count as successes
 * (the provider was reachable). A probe that never reports back (e.g. its task was rejected)
 * expires after {@code callTimeout + openDuration} so the breaker cannot get stuck.
 *
 * <p>All state changes are synchronized; contention is negligible (a few calls per request).
 */
@Component
public class AiCircuitBreaker {

    private static final Logger log = LoggerFactory.getLogger(AiCircuitBreaker.class);

    /** Circuit state. */
    public enum State { CLOSED, OPEN, HALF_OPEN }

    private final int failureThreshold;
    private final Duration openDuration;
    private final Duration probeExpiry;
    private final Clock clock;

    private State state = State.CLOSED;
    private int consecutiveFailures;
    private Instant openedAt;
    private boolean probeInFlight;
    private Instant probeStartedAt;
    private Instant lastSuccessAt;
    private Instant lastFailureAt;
    private FailureKind lastFailureKind;

    public AiCircuitBreaker(AiProperties properties, Clock clock) {
        this.failureThreshold = properties.circuit().failureThreshold();
        this.openDuration = properties.circuit().openDuration();
        this.probeExpiry = properties.callTimeout().plus(openDuration);
        this.clock = clock;
    }

    /** True if a model call may be made now (consumes the single probe when HALF_OPEN). */
    public synchronized boolean allowRequest() {
        Instant now = clock.instant();
        switch (state) {
            case CLOSED:
                return true;
            case OPEN:
                if (now.isBefore(openedAt.plus(openDuration))) {
                    return false;
                }
                state = State.HALF_OPEN;
                return grantProbe(now);
            case HALF_OPEN:
            default:
                if (probeInFlight && now.isBefore(probeStartedAt.plus(probeExpiry))) {
                    return false;
                }
                return grantProbe(now);
        }
    }

    private boolean grantProbe(Instant now) {
        probeInFlight = true;
        probeStartedAt = now;
        return true;
    }

    /** The provider answered (successfully, or with a refusal / invalid output). */
    public synchronized void recordSuccess() {
        lastSuccessAt = clock.instant();
        consecutiveFailures = 0;
        probeInFlight = false;
        if (state != State.CLOSED) {
            state = State.CLOSED;
            openedAt = null;
            log.info("AI circuit closed: the provider is answering again");
        }
    }

    /** A provider failure of unspecified kind. */
    public void recordFailure() {
        recordFailure(FailureKind.PROVIDER_ERROR);
    }

    /** A provider failure (timeout or error). */
    public synchronized void recordFailure(FailureKind kind) {
        Instant now = clock.instant();
        lastFailureAt = now;
        lastFailureKind = kind;
        consecutiveFailures++;
        probeInFlight = false;
        switch (state) {
            case HALF_OPEN -> open(now);
            case OPEN -> openedAt = now; // late failures (e.g. regenerate probes) extend the open period
            case CLOSED -> {
                if (consecutiveFailures >= failureThreshold) {
                    open(now);
                }
            }
        }
    }

    private void open(Instant now) {
        state = State.OPEN;
        openedAt = now;
        log.warn("AI circuit opened after {} consecutive provider failure(s) (last: {}); "
                        + "using template explanations for {}", consecutiveFailures, lastFailureKind, openDuration);
    }

    /** Current state (an expired OPEN period reports HALF_OPEN without consuming the probe). */
    public synchronized State state() {
        if (state == State.OPEN && !clock.instant().isBefore(openedAt.plus(openDuration))) {
            return State.HALF_OPEN;
        }
        return state;
    }

    public synchronized int consecutiveFailures() {
        return consecutiveFailures;
    }

    public synchronized Instant lastSuccessAt() {
        return lastSuccessAt;
    }

    public synchronized Instant lastFailureAt() {
        return lastFailureAt;
    }

    public synchronized FailureKind lastFailureKind() {
        return lastFailureKind;
    }
}
