package com.talentmatch.feed.source;

import java.time.Duration;
import java.util.Objects;

/**
 * Why a fetch or probe failed. Carries no response body and no URL.
 *
 * @param kind       the failure class (drives backoff, §6.1)
 * @param httpStatus the HTTP status, when the provider answered
 * @param retryAfter the provider's {@code Retry-After}, when sent (429 / 503); never negative
 */
public record SourceFailure(Kind kind, Integer httpStatus, Duration retryAfter) {

    public enum Kind {
        RATE_LIMITED,
        NOT_FOUND,
        SERVER_ERROR,
        NETWORK,
        TIMEOUT,
        INVALID_RESPONSE,
        TOO_LARGE,
        UNAUTHORIZED
    }

    public SourceFailure {
        Objects.requireNonNull(kind, "kind");
        if (retryAfter != null && retryAfter.isNegative()) {
            retryAfter = Duration.ZERO;
        }
    }

    public static SourceFailure of(Kind kind) {
        return new SourceFailure(kind, null, null);
    }

    public static SourceFailure of(Kind kind, int httpStatus) {
        return new SourceFailure(kind, httpStatus, null);
    }
}
