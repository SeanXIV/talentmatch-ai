package com.talentmatch.feed.source;

import com.talentmatch.feed.source.SourceFailure.Kind;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.zip.GZIPInputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * GET for job-board APIs (§4.2, corrected by the probe findings):
 * <ul>
 *   <li>Sends {@code User-Agent}, {@code Accept: application/json}, {@code Accept-Encoding: gzip} and,
 *       when stored, {@code If-None-Match}. {@code If-Modified-Since} is never sent: no provider honours
 *       it. 304 → not modified.</li>
 *   <li>The status is checked before the body is used, so a non-JSON error body (Ashby's
 *       {@code 404 text/plain}) is never parsed. Error bodies are discarded unread.</li>
 *   <li>Body cap {@code max-body-bytes} on the bytes received <em>and</em> after gzip decompression,
 *       so uncompressed bodies (Lever never gzips) are capped too. Over it → TOO_LARGE.</li>
 *   <li>One deadline for the whole exchange (connect + read timeout), body included; a slow-drip
 *       body can't hold a poll thread.</li>
 *   <li>sha256 of the body; equal to the stored hash → not modified (covers missing ETags).</li>
 *   <li>At most one request start per host every {@code min-host-spacing}.</li>
 *   <li>Logs and exception messages hold the host and path only, never the query (Adzuna keys travel
 *       in it) and never the body.</li>
 * </ul>
 * Built on the JDK {@link HttpClient} directly (not Spring's {@code RestClient}): it gives one
 * cancellable whole-response deadline and unwrapped exception types for the failure mapping.
 */
@Component
public class SourceHttpClient {

    private static final Logger log = LoggerFactory.getLogger(SourceHttpClient.class);
    private static final int BUFFER_SIZE = 8192;
    private static final long MAX_RETRY_AFTER_SECONDS = Duration.ofDays(7).toSeconds();

    private final SourceProperties.Http http;
    private final Clock clock;
    private final HttpClient client;
    private final ConcurrentHashMap<String, Long> nextSlotNanosByHost = new ConcurrentHashMap<>();

    public SourceHttpClient(SourceProperties properties, Clock clock) {
        this.http = properties.http();
        this.clock = clock;
        this.client = HttpClient.newBuilder()
                .connectTimeout(http.connectTimeout())
                .followRedirects(HttpClient.Redirect.NORMAL)
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }

    public SourceResponse get(URI uri, FetchRequest request) {
        return get(uri, Map.of(), request);
    }

    /**
     * @param uri     absolute http(s) URI built from configured hosts and validated tokens
     * @param headers extra request headers (nullable)
     * @param request the stored conditional state (nullable = unconditional)
     * @throws SourceException for every non-2xx/304 status, network failure, timeout or oversized body
     */
    public SourceResponse get(URI uri, Map<String, String> headers, FetchRequest request) {
        FetchRequest stored = request == null ? FetchRequest.NONE : request;
        String where = describe(uri);
        long started = System.nanoTime();
        awaitHostSlot(uri, where);

        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .GET()
                .timeout(http.readTimeout())
                .header("User-Agent", http.userAgent())
                .header("Accept", "application/json")
                .header("Accept-Encoding", "gzip");
        if (headers != null) {
            headers.forEach(builder::setHeader);
        }
        if (isSafeHeaderValue(stored.etag())) {
            builder.setHeader("If-None-Match", stored.etag());
        }

        HttpResponse<byte[]> response = send(builder.build(), where);
        int status = response.statusCode();
        HttpHeaders responseHeaders = response.headers();
        String etag = responseHeaders.firstValue("ETag").orElse(null);
        String lastModified = responseHeaders.firstValue("Last-Modified").orElse(null);

        if (status == 304) {
            log.debug("Feed GET {} -> 304 ({} ms)", where, elapsedMs(started));
            return new SourceResponse(true, status, null, etag != null ? etag : stored.etag(),
                    lastModified != null ? lastModified : stored.lastModified(), stored.lastBodyHash());
        }
        if (status < 200 || status > 299) {
            log.debug("Feed GET {} -> {} ({} ms)", where, status, elapsedMs(started));
            throw statusFailure(status, responseHeaders, where);
        }

        byte[] body = decode(response.body(), responseHeaders.firstValue("Content-Encoding").orElse(null), where);
        String hash = sha256(body);
        boolean unchanged = hash.equals(stored.lastBodyHash());
        log.debug("Feed GET {} -> {} ({} bytes, {} ms{})", where, status, body.length, elapsedMs(started),
                unchanged ? ", same body" : "");
        return new SourceResponse(unchanged, status, unchanged ? null : body, etag, lastModified, hash);
    }

    private HttpResponse<byte[]> send(HttpRequest request, String where) {
        CompletableFuture<HttpResponse<byte[]>> future = client.sendAsync(request, this::bodySubscriber);
        long deadlineMs = http.connectTimeout().plus(http.readTimeout()).toMillis();
        try {
            return future.get(deadlineMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new SourceException(SourceFailure.of(Kind.TIMEOUT), "Timed out after " + deadlineMs + " ms: " + where);
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new SourceException(SourceFailure.of(Kind.NETWORK), "Interrupted while fetching " + where);
        } catch (ExecutionException e) {
            throw exceptionFailure(e.getCause(), where);
        }
    }

    /** 2xx bodies are read with the cap; any other body is discarded unread. */
    private HttpResponse.BodySubscriber<byte[]> bodySubscriber(HttpResponse.ResponseInfo info) {
        int status = info.statusCode();
        if (status >= 200 && status <= 299) {
            return new BoundedBodySubscriber(http.maxBodyBytes());
        }
        return HttpResponse.BodySubscribers.<byte[]>replacing(null);
    }

    private SourceException exceptionFailure(Throwable thrown, String where) {
        Throwable cause = thrown;
        while ((cause instanceof CompletionException || cause instanceof ExecutionException
                || cause instanceof UncheckedIOException) && cause.getCause() != null) {
            cause = cause.getCause();
        }
        if (cause instanceof BodyTooLargeException) {
            return tooLarge(where);
        }
        if (cause instanceof HttpTimeoutException) {
            return new SourceException(SourceFailure.of(Kind.TIMEOUT), "Timed out: " + where);
        }
        // The class name only: JDK messages can echo addresses, and never matter for the backoff.
        String type = cause == null ? "unknown" : cause.getClass().getSimpleName();
        return new SourceException(SourceFailure.of(Kind.NETWORK), "Network error (" + type + "): " + where);
    }

    private SourceException statusFailure(int status, HttpHeaders headers, String where) {
        Kind kind;
        if (status == 429) {
            kind = Kind.RATE_LIMITED;
        } else if (status == 404 || status == 410) {
            kind = Kind.NOT_FOUND;
        } else if (status == 401 || status == 403) {
            kind = Kind.UNAUTHORIZED;
        } else if (status >= 500) {
            kind = Kind.SERVER_ERROR;
        } else {
            kind = Kind.INVALID_RESPONSE;
        }
        Duration retryAfter = status == 429 || status == 503
                ? parseRetryAfter(headers.firstValue("Retry-After").orElse(null), clock.instant())
                : null;
        return new SourceException(new SourceFailure(kind, status, retryAfter),
                "HTTP " + status + " (" + kind + ") from " + where);
    }

    /**
     * {@code Retry-After} as delay seconds or an HTTP date (RFC 1123). Null when absent or unreadable;
     * a date in the past gives zero; very large values are capped at 7 days.
     */
    static Duration parseRetryAfter(String value, Instant now) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String v = value.strip();
        if (v.chars().allMatch(c -> c >= '0' && c <= '9')) {
            long seconds = v.length() > 9 ? MAX_RETRY_AFTER_SECONDS : Long.parseLong(v);
            return Duration.ofSeconds(Math.min(seconds, MAX_RETRY_AFTER_SECONDS));
        }
        try {
            Instant at = ZonedDateTime.parse(v, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
            Duration delay = Duration.between(now, at);
            if (delay.isNegative()) {
                return Duration.ZERO;
            }
            return delay.toSeconds() > MAX_RETRY_AFTER_SECONDS ? Duration.ofSeconds(MAX_RETRY_AFTER_SECONDS) : delay;
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private byte[] decode(byte[] raw, String contentEncoding, String where) {
        byte[] body = raw == null ? new byte[0] : raw;
        String encoding = contentEncoding == null ? "" : contentEncoding.strip().toLowerCase(Locale.ROOT);
        if (encoding.isEmpty() || encoding.equals("identity")) {
            return body;
        }
        if (!encoding.equals("gzip") && !encoding.equals("x-gzip")) {
            throw new SourceException(SourceFailure.of(Kind.INVALID_RESPONSE),
                    "Unsupported Content-Encoding '" + encoding + "' from " + where);
        }
        try (InputStream in = new GZIPInputStream(new ByteArrayInputStream(body), BUFFER_SIZE)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream(
                    (int) Math.min(http.maxBodyBytes(), Math.max(BUFFER_SIZE, body.length * 4L)));
            byte[] buffer = new byte[BUFFER_SIZE];
            long total = 0;
            int n;
            while ((n = in.read(buffer)) != -1) {
                total += n;
                if (total > http.maxBodyBytes()) {
                    throw tooLarge(where);
                }
                out.write(buffer, 0, n);
            }
            return out.toByteArray();
        } catch (IOException e) {
            throw new SourceException(SourceFailure.of(Kind.INVALID_RESPONSE), "Corrupt gzip body from " + where);
        }
    }

    private SourceException tooLarge(String where) {
        return new SourceException(SourceFailure.of(Kind.TOO_LARGE),
                "Body larger than " + http.maxBodyBytes() + " bytes from " + where);
    }

    /** Waits until this host may start another request (per-host politeness, §4.1). */
    private void awaitHostSlot(URI uri, String where) {
        long spacing = http.minHostSpacing().toNanos();
        if (spacing <= 0) {
            return;
        }
        String host = uri.getHost() + ":" + uri.getPort();
        long now = System.nanoTime();
        long[] wait = new long[1];
        nextSlotNanosByHost.compute(host, (h, next) -> {
            long start = next == null || next - now <= 0 ? now : next;
            wait[0] = start - now;
            return start + spacing;
        });
        if (wait[0] > 0) {
            try {
                TimeUnit.NANOSECONDS.sleep(wait[0]);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new SourceException(SourceFailure.of(Kind.NETWORK), "Interrupted while waiting for " + where);
            }
        }
    }

    /** "host[:port]/path": never the scheme's credentials, the query or the fragment. */
    static String describe(URI uri) {
        String host = uri.getHost() == null ? "?" : uri.getHost();
        String port = uri.getPort() == -1 ? "" : ":" + uri.getPort();
        String path = uri.getRawPath() == null ? "" : uri.getRawPath();
        return host + port + path;
    }

    static String sha256(byte[] body) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    private static boolean isSafeHeaderValue(String value) {
        return value != null && !value.isBlank() && value.length() <= 300
                && value.chars().noneMatch(c -> c < 0x20 || c == 0x7f);
    }

    private static long elapsedMs(long startedNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);
    }

    /** Marker for the cap; mapped to TOO_LARGE. */
    static final class BodyTooLargeException extends IOException {
        BodyTooLargeException() {
            super("body over the cap");
        }
    }

    /** Collects the body and fails (cancelling the exchange) as soon as it passes {@code max} bytes. */
    static final class BoundedBodySubscriber implements HttpResponse.BodySubscriber<byte[]> {

        private final long max;
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();
        private Flow.Subscription subscription;
        private long received;

        BoundedBodySubscriber(long max) {
            this.max = max;
        }

        @Override
        public CompletionStage<byte[]> getBody() {
            return result;
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            subscription.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(List<ByteBuffer> items) {
            if (result.isDone()) {
                return;
            }
            for (ByteBuffer item : items) {
                int n = item.remaining();
                received += n;
                if (received > max) {
                    result.completeExceptionally(new BodyTooLargeException());
                    subscription.cancel();
                    return;
                }
                byte[] chunk = new byte[n];
                item.get(chunk);
                out.write(chunk, 0, n);
            }
        }

        @Override
        public void onError(Throwable throwable) {
            result.completeExceptionally(throwable);
        }

        @Override
        public void onComplete() {
            result.complete(out.toByteArray());
        }
    }
}
