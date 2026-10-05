package com.talentmatch.ai;

import com.fasterxml.jackson.core.JsonProcessingException;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeoutException;

/** Why an AI explanation could not be produced. */
public enum FailureKind {
    /** The model call timed out. Counts as a circuit-breaker failure. */
    TIMEOUT,
    /** The provider failed or was unreachable. Counts as a circuit-breaker failure. */
    PROVIDER_ERROR,
    /** The model stopped for a non-normal reason (content filter, refusal, ...). */
    REFUSED,
    /** The reply could not be parsed, was truncated, or failed validation. */
    INVALID_OUTPUT;

    private static final int MAX_DEPTH = 32;

    /** True if the provider itself was the problem (feeds the circuit breaker). */
    public boolean providerFault() {
        return this == TIMEOUT || this == PROVIDER_ERROR;
    }

    /** Lower-case metric/log tag, e.g. {@code provider_error}. */
    public String tag() {
        return name().toLowerCase(Locale.ROOT);
    }

    /**
     * Classifies an exception thrown by a model call by walking its cause chain: any timeout type
     * (or a class whose simple name contains "Timeout") is TIMEOUT; otherwise any Jackson
     * processing exception (or a simple name containing "Parsing") is INVALID_OUTPUT; anything
     * else is PROVIDER_ERROR.
     */
    public static FailureKind classify(Throwable failure) {
        if (anyInChain(failure, FailureKind::isTimeout)) {
            return TIMEOUT;
        }
        if (anyInChain(failure, FailureKind::isParsing)) {
            return INVALID_OUTPUT;
        }
        return PROVIDER_ERROR;
    }

    private static boolean isTimeout(Throwable t) {
        return t instanceof TimeoutException
                || t instanceof HttpTimeoutException
                || t instanceof SocketTimeoutException
                || t.getClass().getSimpleName().contains("Timeout");
    }

    private static boolean isParsing(Throwable t) {
        return t instanceof JsonProcessingException
                || t.getClass().getSimpleName().contains("Parsing");
    }

    private static boolean anyInChain(Throwable failure, java.util.function.Predicate<Throwable> test) {
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        int depth = 0;
        for (Throwable t = failure; t != null && depth < MAX_DEPTH && seen.add(t); t = t.getCause(), depth++) {
            if (test.test(t)) {
                return true;
            }
        }
        return false;
    }
}
