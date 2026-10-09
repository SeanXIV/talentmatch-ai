package com.talentmatch.web.error;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import com.talentmatch.service.exception.ApiException;
import com.talentmatch.service.exception.FeedPollRateLimitedException;
import com.talentmatch.service.exception.MatchesBusyException;
import com.talentmatch.service.exception.RecomputeAlreadyRunningException;
import com.talentmatch.service.exception.RegenerateRateLimitedException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Path;
import java.net.SocketException;
import java.net.URI;
import java.sql.SQLException;
import java.sql.SQLNonTransientConnectionException;
import java.sql.SQLTransientConnectionException;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
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
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionSystemException;
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
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
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
    static final String SERVICE_BUSY_MESSAGE =
            "The server is busy with a conflicting update. Please try again in a few seconds.";
    static final int SERVICE_BUSY_RETRY_AFTER_SECONDS = 2;
    private static final Pattern MATCHES_PATH = Pattern.compile("^/api/(jobs/[^/]+/matches|matches)(/.*)?$");
    private static final String EXAMPLE_ID = "3f2c0e9a-1b2c-4d5e-8f90-a1b2c3d4e5f6";
    private static final Pattern CONSTRAINT_IN_MESSAGE = Pattern.compile("constraint \"([^\"]+)\"");
    private static final int MAX_ECHO = 64;
    private static final int MAX_CAUSES_INSPECTED = 64;
    private static final String HIKARI_CLOSED_CONNECTION = "Connection is closed";

    /**
     * Unique constraint / index name (verified against V1__init_schema.sql; the job key is the V5
     * partial index over MANUAL jobs; feed_source_source_key_key is PostgreSQL's name for the V5
     * column-level UNIQUE on feed_source.source_key) -> conflict.
     */
    private static final Map<String, Conflict> CONFLICTS = Map.of(
            "uq_candidate_email", new Conflict(ErrorCode.EMAIL_ALREADY_EXISTS,
                    "A candidate with this email already exists."),
            "uq_job_title_company_manual", new Conflict(ErrorCode.JOB_ALREADY_EXISTS,
                    "A job with this title and company already exists."),
            "uq_skill_name_lower", new Conflict(ErrorCode.SKILL_ALREADY_EXISTS,
                    "A skill with this name already exists (names are case-insensitive)."),
            "uq_skill_alias_lower", new Conflict(ErrorCode.SKILL_ALIAS_ALREADY_EXISTS,
                    "This alias already exists (aliases are case-insensitive)."),
            // Safety net: FeedSourceService inserts with ON CONFLICT and reports the existing id itself.
            "feed_source_source_key_key", new Conflict(ErrorCode.FEED_SOURCE_ALREADY_EXISTS,
                    "This source is already on your watchlist. List your sources with GET /api/feed/sources."));

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

    @ExceptionHandler(RegenerateRateLimitedException.class)
    ResponseEntity<ApiError> regenerateRateLimited(RegenerateRateLimitedException ex, HttpServletRequest req) {
        log.info("Regenerate rate-limited on {} (retry after {}s)", req.getRequestURI(), ex.getRetryAfterSeconds());
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.RETRY_AFTER, String.valueOf(ex.getRetryAfterSeconds()));
        return respond(ex.getStatus(), ex.getCode(), ex.getMessage(), ex.getFieldErrors(), req, headers);
    }

    @ExceptionHandler(FeedPollRateLimitedException.class)
    ResponseEntity<ApiError> feedPollRateLimited(FeedPollRateLimitedException ex, HttpServletRequest req) {
        log.info("Feed poll rate-limited on {} (retry after {}s)", req.getRequestURI(), ex.getRetryAfterSeconds());
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.RETRY_AFTER, String.valueOf(ex.getRetryAfterSeconds()));
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
        boolean upload = ex.getSupportedMediaTypes().stream().anyMatch(MediaType.MULTIPART_FORM_DATA::includes);
        String hint = upload
                ? "Upload the file as multipart/form-data, in a form field named 'file'."
                : "Send JSON with Content-Type: application/json.";
        return respond(HttpStatus.UNSUPPORTED_MEDIA_TYPE, ErrorCode.UNSUPPORTED_MEDIA_TYPE,
                "Content type '" + type + "' is not supported. " + hint, List.of(), req, null);
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<ApiError> uploadTooLarge(MaxUploadSizeExceededException ex, HttpServletRequest req) {
        return respond(HttpStatus.PAYLOAD_TOO_LARGE, ErrorCode.PAYLOAD_TOO_LARGE,
                "The uploaded file is too large. A CV must be a PDF of at most a few megabytes; export it again "
                        + "with smaller images, or remove pages that aren't your CV.", List.of(), req, null);
    }

    @ExceptionHandler(MultipartException.class)
    ResponseEntity<ApiError> badMultipart(MultipartException ex, HttpServletRequest req) {
        return respond(HttpStatus.BAD_REQUEST, ErrorCode.MALFORMED_REQUEST,
                "The upload could not be read. Send it as multipart/form-data with the file in a form field "
                        + "named 'file'.", List.of(), req, null);
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

    /**
     * A lock timeout or deadlock. On the matches endpoints it means another request is recomputing
     * the job's matches (MATCHES_BUSY); anywhere else the generic SERVICE_BUSY, so e.g. a preferences
     * save never claims matches are being recalculated.
     */
    @ExceptionHandler(PessimisticLockingFailureException.class)
    ResponseEntity<ApiError> lockFailure(PessimisticLockingFailureException ex, HttpServletRequest req) {
        log.info("Lock failure on {}: {}", req.getRequestURI(), ex.getClass().getSimpleName());
        if (isMatchesEndpoint(req)) {
            return busy(req);
        }
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.RETRY_AFTER, String.valueOf(SERVICE_BUSY_RETRY_AFTER_SECONDS));
        return respond(HttpStatus.SERVICE_UNAVAILABLE, ErrorCode.SERVICE_BUSY, SERVICE_BUSY_MESSAGE,
                List.of(), req, headers);
    }

    /** {@code /api/jobs/{id}/matches} and {@code /api/matches/**}. */
    static boolean isMatchesEndpoint(HttpServletRequest req) {
        String uri = req.getRequestURI();
        return uri != null && MATCHES_PATH.matcher(uri).matches();
    }

    @ExceptionHandler({DataAccessResourceFailureException.class, CannotCreateTransactionException.class,
            QueryTimeoutException.class, TransientDataAccessResourceException.class})
    ResponseEntity<ApiError> databaseUnavailable(Exception ex, HttpServletRequest req) {
        log.warn("Database unavailable on {} (request {}): {}", req.getRequestURI(),
                RequestIdFilter.currentRequestId(req), ex.getMessage());
        return databaseUnavailable(req);
    }

    // ------------------------------------------------------------------ 500

    /** Endpoints whose failures may carry personal data (CV text, profile) in exception messages. */
    private static final String PII_PATH_PREFIX = "/api/profile";

    private static String classChain(Throwable ex) {
        StringBuilder sb = new StringBuilder();
        int depth = 0;
        for (Throwable t = ex; t != null && depth < 16; t = t.getCause() == t ? null : t.getCause(), depth++) {
            if (!sb.isEmpty()) {
                sb.append(" <- ");
            }
            sb.append(t.getClass().getName());
            if (t instanceof java.sql.SQLException sql && sql.getSQLState() != null) {
                sb.append("[SQLState ").append(sql.getSQLState()).append(']');
            }
        }
        return sb.toString();
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> unexpected(Exception ex, HttpServletRequest req) {
        // Before the 500 path: covers wrappers such as JpaSystemException/TransactionSystemException
        // thrown when a rollback on a dead connection overrides the original failure.
        if (isDatabaseUnavailable(ex)) {
            log.warn("Database unavailable on {} (request {}): {}: {}", req.getRequestURI(),
                    RequestIdFilter.currentRequestId(req), ex.getClass().getSimpleName(), ex.getMessage());
            log.debug("Database unavailable detail", ex);
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
        if (req.getRequestURI().startsWith(PII_PATH_PREFIX)) {
            // Profile requests carry CV data, and exception messages (PostgreSQL row details, JSON
            // parse errors) can quote it: log the exception classes only.
            log.error("Unexpected error on {} {} (request {}): {}", req.getMethod(), req.getRequestURI(), requestId,
                    classChain(ex));
        } else {
            log.error("Unexpected error on {} {} (request {})", req.getMethod(), req.getRequestURI(), requestId, ex);
        }
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

    /**
     * True if anything reachable from {@code ex} shows the database (or the connection to it) is
     * gone. Walks causes, suppressed exceptions, {@link SQLException#getNextException()} and
     * {@link TransactionSystemException#getApplicationException()}, guarding against cycles.
     *
     * <p>This matters when a dead connection makes the rollback fail too: TransactionInterceptor
     * then throws the rollback failure (e.g. {@code JpaSystemException: Unable to rollback against
     * JDBC Connection}) instead of the original error, so only the rollback chain is available.
     */
    static boolean isDatabaseUnavailable(Throwable ex) {
        Deque<Throwable> pending = new ArrayDeque<>();
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        if (ex != null) {
            pending.push(ex);
        }
        while (!pending.isEmpty() && seen.size() < MAX_CAUSES_INSPECTED) {
            Throwable t = pending.pop();
            if (!seen.add(t)) {
                continue;
            }
            if (isDatabaseUnavailableSignal(t)) {
                return true;
            }
            if (t.getCause() != null) {
                pending.push(t.getCause());
            }
            for (Throwable s : t.getSuppressed()) {
                pending.push(s);
            }
            if (t instanceof SQLException sql && sql.getNextException() != null) {
                pending.push(sql.getNextException());
            }
            if (t instanceof TransactionSystemException tse && tse.getApplicationException() != null) {
                pending.push(tse.getApplicationException());
            }
        }
        return false;
    }

    private static boolean isDatabaseUnavailableSignal(Throwable t) {
        if (t instanceof CannotGetJdbcConnectionException
                || t instanceof DataAccessResourceFailureException
                || t instanceof CannotCreateTransactionException
                || t instanceof QueryTimeoutException
                || t instanceof org.hibernate.exception.JDBCConnectionException
                || t instanceof SocketException // includes ConnectException
                || t instanceof SQLTransientConnectionException
                || t instanceof SQLNonTransientConnectionException) {
            return true;
        }
        if (t instanceof SQLException sql) {
            String state = sql.getSQLState();
            if (state != null) {
                // 08xxx: connection exception; 57P0x: server shutting down / admin terminated.
                return state.startsWith("08") || state.startsWith("57P0");
            }
            // HikariCP's closed-connection proxy (used after the pool evicts a broken connection)
            // throws this without a SQLState in some versions.
            return HIKARI_CLOSED_CONNECTION.equals(sql.getMessage());
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
