package com.talentmatch.web.error;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;

/**
 * Error body shared by every endpoint (and by {@code /error}).
 *
 * @param status      HTTP status code
 * @param error       HTTP reason phrase
 * @param code        machine-readable code
 * @param message     human-readable, actionable message (never stack traces, class names or SQL)
 * @param path        request path
 * @param timestamp   when the error occurred
 * @param requestId   correlation id (also in the X-Request-Id header)
 * @param fieldErrors per-field problems, sorted by field; omitted when empty
 */
public record ApiError(
        int status,
        String error,
        String code,
        String message,
        String path,
        Instant timestamp,
        String requestId,
        @JsonInclude(JsonInclude.Include.NON_EMPTY) List<FieldErrorDto> fieldErrors) {

    public ApiError {
        fieldErrors = fieldErrors == null ? List.of()
                : fieldErrors.stream().sorted(FieldErrorDto.ORDER).toList();
    }
}
