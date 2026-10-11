package com.talentmatch.feed.source;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.talentmatch.feed.source.SourceFailure.Kind;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Shared, lenient JSON reading for the adapters. A <em>local</em> mapper (the app's mapper fails on
 * unknown properties); tree reading, so new provider fields never break parsing. Every accessor
 * returns null for missing, null or unusable values instead of throwing.
 */
final class SourceJson {

    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .disable(StreamReadFeature.INCLUDE_SOURCE_IN_LOCATION)   // no body text in messages
            .build();

    /** Board tokens: the V5 CHECK pattern, and never only dots ("." / ".." would change the path). */
    private static final Pattern TOKEN = Pattern.compile("^[A-Za-z0-9._-]{1,100}$");
    private static final Pattern ONLY_DOTS = Pattern.compile("^\\.+$");

    private SourceJson() {
    }

    /** Parses a 2xx body. Malformed or empty → INVALID_RESPONSE (the message holds no body text). */
    static JsonNode parse(byte[] body, String where) {
        try {
            JsonNode root = body == null || body.length == 0 ? null : MAPPER.readTree(body);
            if (root == null || root.isMissingNode()) {
                throw invalid("Empty body from " + where);
            }
            return root;
        } catch (IOException e) {
            throw invalid("Malformed JSON from " + where);
        }
    }

    /** The postings array, checked against the per-source cap. */
    static JsonNode requireArray(JsonNode node, String what, int maxPostings, String where) {
        if (node == null || !node.isArray()) {
            throw invalid("Expected " + what + " to be an array in the response from " + where);
        }
        if (node.size() > maxPostings) {
            throw invalid(node.size() + " postings exceed the limit of " + maxPostings + " from " + where);
        }
        return node;
    }

    static SourceException invalid(String message) {
        return new SourceException(SourceFailure.of(Kind.INVALID_RESPONSE), message);
    }

    /** Stripped text of a scalar field; numbers and booleans as text. Null when missing or blank. */
    static String text(JsonNode parent, String field) {
        return parent == null ? null : text(parent.get(field));
    }

    static String text(JsonNode node) {
        if (node == null || !node.isValueNode() || node.isNull()) {
            return null;
        }
        String value = node.asText().strip();
        return value.isEmpty() ? null : value;
    }

    static BigDecimal decimal(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isNumber()) {
            return node.decimalValue();
        }
        String value = text(node);
        if (value == null) {
            return null;
        }
        try {
            return new BigDecimal(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** True/false for a JSON boolean, else null. */
    static Boolean bool(JsonNode node) {
        return node == null || !node.isBoolean() ? null : node.booleanValue();
    }

    /** An ISO-8601 timestamp with offset ("…-04:00", "…+00:00", "…Z") or epoch milliseconds. */
    static Instant instant(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isIntegralNumber()) {
            try {
                return Instant.ofEpochMilli(node.longValue());
            } catch (DateTimeException e) {
                return null;
            }
        }
        String value = text(node);
        if (value == null) {
            return null;
        }
        try {
            return OffsetDateTime.parse(value).toInstant();
        } catch (DateTimeException e) {
            try {
                return Instant.parse(value);
            } catch (DateTimeException ignored) {
                return null;
            }
        }
    }

    /** The URL if it is absolute http(s) with a host, else null ({@code javascript:}, relative, garbage). */
    static String httpUrl(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            URI uri = new URI(value.strip());
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if ((scheme.equals("http") || scheme.equals("https")) && uri.getHost() != null) {
                return uri.toString();
            }
            return null;
        } catch (URISyntaxException e) {
            return null;
        }
    }

    /** A board token safe to put in a URL path, else IllegalArgumentException. */
    static String requireToken(String token) {
        if (token == null || !TOKEN.matcher(token).matches() || ONLY_DOTS.matcher(token).matches()) {
            throw new IllegalArgumentException("Board token must match [A-Za-z0-9._-]{1,100} and not be only dots");
        }
        return token;
    }
}
