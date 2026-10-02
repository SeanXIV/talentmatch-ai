package com.talentmatch.service.exception;

import com.talentmatch.web.error.ErrorCode;
import com.talentmatch.web.error.FieldErrorDto;
import java.util.List;
import org.springframework.http.HttpStatus;

/** Base class for errors that map directly to an {@code ApiError} response. */
public class ApiException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final ErrorCode code;
    private final HttpStatus status;
    private final transient List<FieldErrorDto> fieldErrors;

    public ApiException(ErrorCode code, HttpStatus status, String message, List<FieldErrorDto> fieldErrors) {
        super(message);
        this.code = code;
        this.status = status;
        this.fieldErrors = fieldErrors == null ? List.of() : List.copyOf(fieldErrors);
    }

    public ApiException(ErrorCode code, HttpStatus status, String message) {
        this(code, status, message, List.of());
    }

    public ErrorCode getCode() {
        return code;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public List<FieldErrorDto> getFieldErrors() {
        return fieldErrors;
    }
}
