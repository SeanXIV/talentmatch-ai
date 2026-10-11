package com.talentmatch.feed.source;

import java.util.Objects;

/**
 * A fetch or probe failure. The message is safe to log and to store in
 * {@code feed_source.last_error}: it names the failure, the HTTP status and at most the host and
 * path of the request. It never holds a response body, a query string (Adzuna keys travel in the
 * query) or a posting's text. The cause is only attached for exceptions that carry none of those.
 */
public class SourceException extends RuntimeException {

    private final SourceFailure failure;

    public SourceException(SourceFailure failure, String safeMessage) {
        this(failure, safeMessage, null);
    }

    public SourceException(SourceFailure failure, String safeMessage, Throwable cause) {
        super(safeMessage, cause);
        this.failure = Objects.requireNonNull(failure, "failure");
    }

    public SourceFailure failure() {
        return failure;
    }

    public SourceFailure.Kind kind() {
        return failure.kind();
    }
}
