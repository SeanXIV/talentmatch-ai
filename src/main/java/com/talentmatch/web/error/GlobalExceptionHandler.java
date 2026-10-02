package com.talentmatch.web.error;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import com.talentmatch.service.exception.ApiException;
import com.talentmatch.service.exception.MatchesBusyException;
import com.talentmatch.service.exception.RecomputeAlreadyRunningException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Path;
import java.net.URI;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.core.MethodParameter;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.validation.FieldError;
import org.springframework.validation.ObjectError;
import org.springframework.validation.method.MethodValidationException;
import org.springframework.validation.method.MethodValidationResult;
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.web.ErrorResponse;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Maps every exception to an {@link ApiError}. Messages are written for API users: no stack
 * traces, exception class names or SQL ever reach the response; unexpected errors are logged
 * server-side with the request id.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    static final String DB_UNAVAILABLE_MESSAGE =
            "The database is temporarily unavailable. Please try again in a moment.";
    static final int DB_RETRY_AFTER_SECONDS = 5;
    private static final String EXAMPLE_ID = "3f2c0e9a-1b2c-4d5e-8f90-a1b2c3d4e5f6";
    private static final Pattern CONSTRAINT_IN_MESSAGE = Pattern.compile("constraint \"([^\"]+)\"");
    private static final int MAX_ECHO = 64;

    /** Unique constraint / index name (verified against V1__init_schema.sql) -> conflict. */
    private static final Map<String, Conflict> CONFLICTS = Map.of(
            "uq_candidate_email", new Conflict(ErrorCode.EMAIL_ALREADY_EXISTS,
                    "A candidate with this email already exists."),
            "uq_job_title_company", new Conflict(ErrorCode.JOB_ALREADY_EXISTS,
                    "A job with this title and company already exists."),
            "uq_skill_name_lower", new Conflict(ErrorCode.SKILL_ALREADY_EXISTS,
                    "A skill with this name already exists (names are case-insensitive)."));

    private record Conflict(ErrorCode code, String message) {
    }

    // ------------------------------------------------------------------ 4xx: application

    @ExceptionHandler(RecomputeAlreadyRunningException.class)
    ResponseEntity<ApiError> recomputeRunning(RecomputeAlreadyRunningException ex, HttpServletRequest req) {
        HttpHeaders headers = new HttpHeaders();
        if (ex.getActiveRunId() != null) {
            headers.setLocation(URI.create("/api/matches/recompute/" + ex.getActiveRunId()));
        }
        return respond(ex.getStatus(), ex.getCode(), ex.getMessage(), ex.getFieldErrors(), req, headers);
    }

    @ExceptionHandler(MatchesBusyException.class)
    ResponseEntity<ApiError> matchesBusy(MatchesBusyException ex, HttpServletRequest req) {
        log.info("Match lock busy for {}", req.getRequestURI());
        return busy(req);
    }

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ApiError> api(ApiException ex, HttpServletRequest req) {
        log.debug("{} {}: {}", ex.getStatus().value(), ex.getCode(), ex.getMessage());
        return respond(ex.getStatus(), ex.getCode(), ex.getMessage(), ex.getFieldErrors(), req, null);
    }

    // ------------------------------------------------------------------ 400: request shape

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiError> bodyInvalid(MethodArgumentNotValidException ex, HttpServletRequest req) {
        List<FieldErrorDto> errors = new ArrayList<>();
        for (FieldError fe : ex.getBindingResult().getFieldErrors()) {
            errors.add(new FieldErrorDto(fe.getField(), fallback(fe.getDefaultMessage(), "Invalid value.")));
        }
        for (ObjectError oe : ex.getBindingResult().getGlobalErrors()) {
            errors.add(new FieldErrorDto(oe.getObjectName(), fallback(oe.getDefaultMessage(), "Invalid value.")));
        }
        // Same de-duplication as invalidParameters; encounter order is preserved.
        List<FieldErrorDto> distinct = errors.stream().distinct().toList();
        return respond(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_FAILED,
                com.talentmatch.service.exception.RequestValidationException.summarize(distinct), distinct, req, null);
    }

    @ExceptionHandler({HandlerMethodValidationException.class, MethodValidationException.class})
    ResponseEntity<ApiError> methodValidation(Exception ex, HttpServletRequest req) {
        List<FieldErrorDto> errors = new ArrayList<>();
        if (ex instanceof MethodValidationResult result) {
            for (ParameterValidationResult pvr : result.getParameterValidationResults()) {
                String name = parameterName(pvr.getMethodParameter());
                for (MessageSourceResolvable error : pvr.getResolvableErrors()) {
                    errors.add(new FieldErrorDto(name, fallback(error.getDefaultMessage(), name + " is invalid.")));
                }
            }
        }
        return invalidParameters(errors, req);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    ResponseEntity<ApiError> constraintViolation(ConstraintViolationException ex, HttpServletRequest req) {
        List<FieldErrorDto> errors = new ArrayList<>();
        Set<ConstraintViolation<?>> violations = ex.getConstraintViolations();
        if (violations != null) {
            for (ConstraintViolation<?> v : violations) {
                String name = lastNode(v.getPropertyPath());
                errors.add(new FieldErrorDto(name, fallback(v.getMessage(), name + " is invalid.")));
            }
        }
        return invalidParameters(errors, req);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<ApiError> typeMismatch(MethodArgumentTypeMismatchException ex, HttpServletRequest req) {
        String name = ex.getName();
        Class<?> type = ex.getRequiredType();
        String value = echo(ex.getValue());
        MethodParameter parameter = ex.getParameter();
        if (UUID.class.equals(type) && parameter.hasParameterAnnotation(PathVariable.class)) {
            return respond(HttpStatus.BAD_REQUEST, ErrorCode.INVALID_ID,
                    "'" + value + "' is not a valid id. Ids look like " + EXAMPLE_ID + ".", List.of(), req, null);
        }
        String message;
        if (type == boolean.class || type == Boolean.class) {
            message = "Parameter '" + name + "' must be true or false.";
        } else if (type == int.class || type == Integer.class || type == long.class || type == Long.class) {
            message = "Parameter '" + name + "' must be a whole number.";
        } else if (type == double.class || type == Double.class || type == float.class || type == Float.class) {
            message = "Parameter '" + name + "' must be a number.";
        } else if (UUID.class.equals(type)) {
            message = "Parameter '" + name + "' must be an id like " + EXAMPLE_ID + ".";
        } else {
            message = "Parameter '" + name + "' has an invalid value '" + value + "'.";
        }
        return invalidParameters(List.of(new FieldErrorDto(name, message)), req);
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    ResponseEntity<ApiError> missingParameter(MissingServletRequestParameterException ex, HttpServletRequest req) {
        String name = ex.getParameterName();
        return invalidParameters(List.of(new FieldErrorDto(name, "Parameter '" + name + "' is required.")), req);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ApiError> notReadable(HttpMessageNotReadableException ex, HttpServletRequest req) {
        String message = "The request body could not be read. Send a valid JSON object.";
        List<FieldErrorDto> errors = List.of();
        Throwable cause = ex.getCause();
        if (cause instanceof UnrecognizedPropertyException u) {
            String path = jsonPath(u);
            message = "Unknown field '" + path + "'.";
            errors = List.of(new FieldErrorDto(path, "Unknown field. Check the spelling (field names are camelCase)."));
        } else if (cause instanceof MismatchedInputException m) {
            String path = jsonPath(m);
            if (path.isEmpty()) {
                message = "The request body has the wrong shape. Send a JSON object.";
            } else {
                message = "Field '" + path + "' has the wrong type.";
                errors = List.of(new FieldErrorDto(path, "Expected " + describeType(m.getTargetType()) + "."));
            }
        } else if (cause instanceof JsonParseException) {
            message = "The request body is not valid JSON.";
        } else if (cause instanceof JsonMappingException jm && !jsonPath(jm).isEmpty()) {
            String path = jsonPath(jm);
            message = "Field '" + path + "' has an invalid value.";
            errors = List.of(new FieldErrorDto(path, "Invalid value."));
        } else if (cause == null && ex.getMessage() != null
                && ex.getMessage().startsWith("Required request body is missing")) {
            message = "The request body is missing. Send a JSON object.";
        }
        return respond(HttpStatus.BAD_REQUEST, ErrorCode.MALFORMED_REQUEST, message, errors, req, null);
    }

    // ------------------------------------------------------------------ 404 / 405 / 406 / 415

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<ApiError> noEndpoint(NoResourceFoundException ex, HttpServletRequest req) {
        return respond(HttpStatus.NOT_FOUND, ErrorCode.ENDPOINT_NOT_FOUND,
                "No endpoint " + req.getMethod() + " " + req.getRequestURI() + ".", List.of(), req, null);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<ApiError> methodNotAllowed(HttpRequestMethodNotSupportedException ex, HttpServletRequest req) {
        HttpHeaders headers = new HttpHeaders();
        Set<HttpMethod> supported = ex.getSupportedHttpMethods();
        String supportedText = "";
        if (supported != null && !supported.isEmpty()) {
            headers.setAllow(supported);
            supportedText = " Supported: " + supported.stream().map(HttpMethod::name).sorted()
                    .collect(Collectors.joining(", ")) + ".";
        }
        return respond(HttpStatus.METHOD_NOT_ALLOWED, ErrorCode.METHOD_NOT_ALLOWED,
                "Method " + req.getMethod() + " is not supported for " + req.getRequestURI() + "." + supportedText,
                List.of(), req, headers);
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ResponseEntity<ApiError> unsupportedMediaType(HttpMediaTypeNotSupportedException ex, HttpServletRequest req) {
        String type = ex.getContentType() == null ? "(none)" : echo(ex.getContentType().toString());
        return respond(HttpStatus.UNSUPPORTED_MEDIA_TYPE, ErrorCode.UNSUPPORTED_MEDIA_TYPE,
                "Content type '" + type + "' is not supported. Send JSON with Content-Type: application/json.",
                List.of(), req, null);
    }

    @ExceptionHandler(HttpMediaTypeNotAcceptableException.class)
    ResponseEntity<Void> notAcceptable(HttpMediaTypeNotAcceptableException ex) {
        // The client does not accept JSON, so no error body can be written.
        return ResponseEntity.status(HttpStatus.NOT_ACCEPTABLE).build();
    }

    // ------------------------------------------------------------------ 409 / 503: database

    @ExceptionHandler({DataIntegrityViolationException.class, org.hibernate.exception.ConstraintViolationException.class})
    ResponseEntity<ApiError> dataIntegrity(Exception ex, HttpServletRequest req) {
        String constraint = constraintName(ex);
        Conflict conflict = constraint == null ? null : CONFLICTS.get(constraint);
        log.info("Data integrity violation (constraint={}) on {}", constraint, req.getRequestURI());
        if (conflict != null) {
            return respond(HttpStatus.CONFLICT, conflict.code(), conflict.message(), List.of(), req, null);
        }
        return respond(HttpStatus.CONFLICT, ErrorCode.DATA_CONFLICT,
                "The change conflicts with existing data (it may have been modified concurrently). "
                        + "Reload and try again.", List.of(), req, null);
    }

    @ExceptionHandler(PessimisticLockingFailureException.class)
    ResponseEntity<ApiError> lockFailure(PessimisticLockingFailureException ex, HttpServletRequest req) {
        log.info("Lock failure on {}: {}", req.getRequestURI(), ex.getClass().getSimpleName());
        return busy(req);
    }

    @ExceptionHandler({DataAccessResourceFailureException.class, CannotCreateTransactionException.class,
            QueryTimeoutException.class, TransientDataAccessResourceException.class})
    ResponseEntity<ApiError> databaseUnavailable(Exception ex, HttpServletRequest req) {
        log.warn("Database unavailable on {} (request {}): {}", req.getRequestURI(),
                RequestIdFilter.currentRequestId(req), ex.getMessage());
        return databaseUnavailable(req);
    }

    // ------------------------------------------------------------------ 500

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> unexpected(Exception ex, HttpServletRequest req) {
        if (isConnectionFailure(ex)) {
            log.warn("Database connection failure on {} (request {})", req.getRequestURI(),
                    RequestIdFilter.currentRequestId(req), ex);
            return databaseUnavailable(req);
        }
        if (ex instanceof ErrorResponse er && er.getStatusCode().is4xxClientError()) {
            HttpStatusCode code = er.getStatusCode();
            HttpStatus status = HttpStatus.resolve(code.value());
            if (status != null) {
                log.debug("Client error {} on {}", status.value(), req.getRequestURI());
                return respond(status, ApiErrorAttributes.codeFor(status),
                        "The request could not be processed (" + status.getReasonPhrase() + ").", List.of(), req, null);
            }
        }
        String requestId = RequestIdFilter.currentRequestId(req);
        log.error("Unexpected error on {} {} (request {})", req.getMethod(), req.getRequestURI(), requestId, ex);
        return respond(HttpStatus.INTERNAL_SERVER_ERROR, ErrorCode.INTERNAL_ERROR,
                "Something went wrong on our side. Quote request id " + requestId + " if you report it.",
                List.of(), req, null);
    }

    // ------------------------------------------------------------------ helpers

    private ResponseEntity<ApiError> busy(HttpServletRequest req) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.RETRY_AFTER, String.valueOf(MatchesBusyException.RETRY_AFTER_SECONDS));
        return respond(HttpStatus.SERVICE_UNAVAILABLE, ErrorCode.MATCHES_BUSY, MatchesBusyException.MESSAGE,
                List.of(), req, headers);
    }

    private ResponseEntity<ApiError> databaseUnavailable(HttpServletRequest req) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.RETRY_AFTER, String.valueOf(DB_RETRY_AFTER_SECONDS));
        return respond(HttpStatus.SERVICE_UNAVAILABLE, ErrorCode.DATABASE_UNAVAILABLE, DB_UNAVAILABLE_MESSAGE,
                List.of(), req, headers);
    }

    private ResponseEntity<ApiError> invalidParameters(List<FieldErrorDto> errors, HttpServletRequest req) {
        // Distinct first: one value can break several constraints with the same message
        // (e.g. minScore=NaN fails both @DecimalMin and @DecimalMax).
        List<FieldErrorDto> sorted = errors.stream().distinct().sorted(FieldErrorDto.ORDER).toList();
        String message = sorted.isEmpty() ? "A query parameter is invalid."
                : sorted.size() == 1 ? sorted.get(0).message()
                : sorted.stream().map(FieldErrorDto::message).collect(Collectors.joining(" "));
        return respond(HttpStatus.BAD_REQUEST, ErrorCode.INVALID_PARAMETER, message, sorted, req, null);
    }

    private static ResponseEntity<ApiError> respond(HttpStatus status, ErrorCode code, String message,
                                                    List<FieldErrorDto> fieldErrors, HttpServletRequest req,
                                                    HttpHeaders headers) {
        ApiError body = new ApiError(status.value(), status.getReasonPhrase(), code.name(), message,
                req.getRequestURI(), Instant.now(), RequestIdFilter.currentRequestId(req), fieldErrors);
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(status);
        if (headers != null) {
            builder.headers(headers);
        }
        return builder.body(body);
    }

    private static String parameterName(MethodParameter parameter) {
        RequestParam rp = parameter.getParameterAnnotation(RequestParam.class);
        if (rp != null && !rp.name().isEmpty()) {
            return rp.name();
        }
        String name = parameter.getParameterName();
        return name == null ? "parameter" : name;
    }

    private static String lastNode(Path path) {
        String name = null;
        if (path != null) {
            for (Path.Node node : path) {
                if (node.getName() != null) {
                    name = node.getName();
                }
            }
        }
        return name == null ? "parameter" : name;
    }

    private static String jsonPath(JsonMappingException e) {
        StringBuilder sb = new StringBuilder();
        for (JsonMappingException.Reference ref : e.getPath()) {
            if (ref.getFieldName() != null) {
                if (sb.length() > 0) {
                    sb.append('.');
                }
                sb.append(ref.getFieldName());
            } else if (ref.getIndex() >= 0) {
                sb.append('[').append(ref.getIndex()).append(']');
            }
        }
        return sb.toString();
    }

    private static String describeType(Class<?> type) {
        if (type == null) {
            return "a different type";
        }
        if (type == Integer.class || type == int.class || type == Long.class || type == long.class) {
            return "a whole number";
        }
        if (type == Boolean.class || type == boolean.class) {
            return "true or false";
        }
        if (type == String.class) {
            return "a string";
        }
        if (Collection.class.isAssignableFrom(type) || type.isArray()) {
            return "a list";
        }
        if (type.isRecord()) {
            return "an object";
        }
        return "a different type";
    }

    private static String constraintName(Throwable ex) {
        for (Throwable t = ex; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof org.hibernate.exception.ConstraintViolationException h && h.getConstraintName() != null) {
                return h.getConstraintName();
            }
            if (t instanceof SQLException && t.getMessage() != null) {
                Matcher m = CONSTRAINT_IN_MESSAGE.matcher(t.getMessage());
                if (m.find()) {
                    return m.group(1);
                }
            }
        }
        return null;
    }

    /** SQLState class 08 (connection exception) or 57P0x (server shutting down) in the cause chain. */
    private static boolean isConnectionFailure(Throwable ex) {
        for (Throwable t = ex; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof SQLException sql && sql.getSQLState() != null
                    && (sql.getSQLState().startsWith("08") || sql.getSQLState().startsWith("57P0"))) {
                return true;
            }
        }
        return false;
    }

    private static String echo(Object value) {
        String s = String.valueOf(value);
        return s.length() <= MAX_ECHO ? s : s.substring(0, MAX_ECHO) + "...";
    }

    private static String fallback(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
