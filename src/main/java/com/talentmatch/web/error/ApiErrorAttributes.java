package com.talentmatch.web.error;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.web.error.ErrorAttributeOptions;
import org.springframework.boot.web.servlet.error.DefaultErrorAttributes;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.WebRequest;

/**
 * Makes Spring Boot's {@code /error} endpoint (errors that never reach a controller, e.g.
 * container-level failures) use the same shape as {@link ApiError}, without exposing exception
 * details.
 */
@Component
public class ApiErrorAttributes extends DefaultErrorAttributes {

    private static final String STATUS_ATTR = "jakarta.servlet.error.status_code";
    private static final String URI_ATTR = "jakarta.servlet.error.request_uri";

    @Override
    public Map<String, Object> getErrorAttributes(WebRequest webRequest, ErrorAttributeOptions options) {
        HttpStatus status = resolveStatus(webRequest);
        Object uri = webRequest.getAttribute(URI_ATTR, RequestAttributes.SCOPE_REQUEST);
        Object requestId = webRequest.getAttribute(RequestIdFilter.ATTRIBUTE, RequestAttributes.SCOPE_REQUEST);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", status.value());
        body.put("error", status.getReasonPhrase());
        body.put("code", codeFor(status).name());
        body.put("message", messageFor(status));
        body.put("path", uri == null ? null : uri.toString());
        body.put("timestamp", Instant.now());
        body.put("requestId", requestId == null ? null : requestId.toString());
        return body;
    }

    private static HttpStatus resolveStatus(WebRequest webRequest) {
        Object code = webRequest.getAttribute(STATUS_ATTR, RequestAttributes.SCOPE_REQUEST);
        if (code instanceof Integer i) {
            HttpStatus resolved = HttpStatus.resolve(i);
            if (resolved != null) {
                return resolved;
            }
        }
        return HttpStatus.INTERNAL_SERVER_ERROR;
    }

    static ErrorCode codeFor(HttpStatus status) {
        return switch (status.value()) {
            case 400 -> ErrorCode.MALFORMED_REQUEST;
            case 404 -> ErrorCode.ENDPOINT_NOT_FOUND;
            case 405 -> ErrorCode.METHOD_NOT_ALLOWED;
            case 406 -> ErrorCode.NOT_ACCEPTABLE;
            case 413 -> ErrorCode.PAYLOAD_TOO_LARGE;
            case 415 -> ErrorCode.UNSUPPORTED_MEDIA_TYPE;
            case 503 -> ErrorCode.DATABASE_UNAVAILABLE;
            default -> status.is5xxServerError() ? ErrorCode.INTERNAL_ERROR : ErrorCode.REQUEST_FAILED;
        };
    }

    private static String messageFor(HttpStatus status) {
        return switch (status.value()) {
            case 400 -> "The request could not be understood. Check the URL, parameters and body.";
            case 404 -> "No endpoint at this path.";
            case 405 -> "This endpoint does not support that HTTP method.";
            case 413 -> "The request body is too large.";
            case 415 -> "Send the request body as application/json.";
            case 503 -> "The service is temporarily unavailable. Please try again in a moment.";
            default -> status.is5xxServerError()
                    ? "Something went wrong on our side. Please try again later."
                    : status.getReasonPhrase() + ".";
        };
    }
}
