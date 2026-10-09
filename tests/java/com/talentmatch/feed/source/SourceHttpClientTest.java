package com.talentmatch.feed.source;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.talentmatch.feed.source.SourceFailure.Kind;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Map;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.slf4j.LoggerFactory;

/**
 * §9.1 item 2 against a local stub. Deliberate deviation from the spec text: If-Modified-Since is
 * asserted to be never sent (probe findings: no provider honours it).
 */
class SourceHttpClientTest {

    private static final Instant NOW = Instant.parse("2026-10-07T10:00:00Z");
    private static final String SECRET = "app_key=SUPERSECRET123";

    private FeedStubServer stub;
    private ListAppender<ILoggingEvent> logs;
    private Logger logger;
    private Level previousLevel;

    @BeforeEach
    void setUp() throws IOException {
        stub = new FeedStubServer();
        logger = (Logger) LoggerFactory.getLogger(SourceHttpClient.class);
        previousLevel = logger.getLevel();
        logger.setLevel(Level.DEBUG);
        logs = new ListAppender<>();
        logs.start();
        logger.addAppender(logs);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(logs);
        logger.setLevel(previousLevel);
        stub.close();
    }

    /** Tight timeouts: the timeout tests depend on these firing well before their stubs finish. */
    private static final Duration TIGHT = Duration.ofSeconds(1);
    /** Body-cap tests are not about timing; a generous timeout keeps them from flaking TIMEOUT under load. */
    private static final Duration GENEROUS = Duration.ofSeconds(15);

    private SourceHttpClient client(Duration timeout, long maxBody) {
        SourceProperties props = new SourceProperties(5000,
                Fixtures.http(timeout, timeout, maxBody, Duration.ZERO), null, null, null);
        return new SourceHttpClient(props, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    /** Client with a small body cap and a generous timeout, for the cap tests. */
    private SourceHttpClient client(long maxBody) {
        return client(GENEROUS, maxBody);
    }

    private SourceHttpClient client() {
        return client(TIGHT, 20_971_520L);
    }

    private URI uri() {
        return stub.uri("/v1/boards/acme/jobs?" + SECRET);
    }

    private SourceException fail(SourceHttpClient c, URI uri) {
        SourceException e = assertThrows(SourceException.class, () -> c.get(uri, FetchRequest.NONE));
        assertNoSecret(e);
        return e;
    }

    private void assertNoSecret(SourceException e) {
        assertThat(e.getMessage()).doesNotContain("SUPERSECRET123").doesNotContain("app_key").doesNotContain("?");
        Throwable cause = e.getCause();
        while (cause != null) {
            assertThat(String.valueOf(cause.getMessage())).doesNotContain("SUPERSECRET123");
            cause = cause.getCause();
        }
    }

    @AfterEach
    void logsNeverHoldTheQuery() {
        assertThat(logs.list).allSatisfy(event -> {
            assertThat(event.getFormattedMessage()).doesNotContain("SUPERSECRET123");
            if (event.getThrowableProxy() != null) {
                assertThat(event.getThrowableProxy().getMessage()).doesNotContain("SUPERSECRET123");
            }
        });
    }

    // ---- request headers and conditional requests ----

    @Test
    void sendsUserAgentAndIfNoneMatchButNeverIfModifiedSince() {
        stub.handler(FeedStubServer.json(200, "{\"jobs\":[]}"));
        client().get(uri(), new FetchRequest("W/\"etag-1\"", "Wed, 01 Oct 2026 10:00:00 GMT", null));
        FeedStubServer.Captured req = stub.last();
        assertThat(req.header("User-Agent")).isEqualTo("TalentMatch-Test/1.0 (qa)");
        assertThat(req.header("If-None-Match")).isEqualTo("W/\"etag-1\"");
        assertThat(req.hasHeader("If-Modified-Since")).isFalse();
        assertThat(req.header("Accept-Encoding")).contains("gzip");
        assertThat(req.uri().getQuery()).isEqualTo(SECRET);
    }

    @Test
    void noConditionalHeadersWithoutStoredState() {
        stub.handler(FeedStubServer.json(200, "{}"));
        client().get(uri(), FetchRequest.NONE);
        assertThat(stub.last().hasHeader("If-None-Match")).isFalse();
        assertThat(stub.last().hasHeader("If-Modified-Since")).isFalse();
    }

    @Test
    void etagWithControlCharactersIsNotSent() {
        stub.handler(FeedStubServer.json(200, "{}"));
        client().get(uri(), new FetchRequest("W/\"a\"\r\nX-Evil: 1", null, null));
        assertThat(stub.last().hasHeader("If-None-Match")).isFalse();
        assertThat(stub.last().hasHeader("X-Evil")).isFalse();
    }

    @Test
    void notModified304KeepsStoredEtagAndHash() {
        stub.handler(FeedStubServer.bytes(304, null, null, Map.of()));
        SourceResponse r = client().get(uri(), new FetchRequest("W/\"stored\"", null, "abc123"));
        assertThat(r.notModified()).isTrue();
        assertThat(r.status()).isEqualTo(304);
        assertThat(r.body()).isNull();
        assertThat(r.etag()).isEqualTo("W/\"stored\"");
        assertThat(r.bodyHash()).isEqualTo("abc123");
    }

    @Test
    void notModified304WithNewEtagReturnsIt() {
        stub.handler(FeedStubServer.bytes(304, null, null, Map.of("ETag", "W/\"fresh\"")));
        SourceResponse r = client().get(uri(), new FetchRequest("W/\"stored\"", null, null));
        assertThat(r.notModified()).isTrue();
        assertThat(r.etag()).isEqualTo("W/\"fresh\"");
    }

    @Test
    void equalBodyHashIsNotModified() {
        stub.handler(FeedStubServer.bytes(200, "application/json", "{\"jobs\":[1]}".getBytes(StandardCharsets.UTF_8),
                Map.of("ETag", "W/\"e1\"")));
        SourceHttpClient c = client();
        SourceResponse first = c.get(uri(), FetchRequest.NONE);
        assertThat(first.notModified()).isFalse();
        assertThat(new String(first.body(), StandardCharsets.UTF_8)).isEqualTo("{\"jobs\":[1]}");
        assertThat(first.bodyHash()).isEqualTo(SourceHttpClient.sha256("{\"jobs\":[1]}".getBytes(StandardCharsets.UTF_8)));
        assertThat(first.etag()).isEqualTo("W/\"e1\"");

        SourceResponse second = c.get(uri(), new FetchRequest(null, null, first.bodyHash()));
        assertThat(second.notModified()).isTrue();
        assertThat(second.body()).isNull();
        assertThat(second.bodyHash()).isEqualTo(first.bodyHash());

        SourceResponse third = c.get(uri(), new FetchRequest(null, null, "different"));
        assertThat(third.notModified()).isFalse();
    }

    // ---- status mapping ----

    @Test
    void rateLimitedWithRetryAfterSeconds() {
        stub.handler(FeedStubServer.bytes(429, "text/plain", "slow down".getBytes(StandardCharsets.UTF_8),
                Map.of("Retry-After", "120")));
        SourceException e = fail(client(), uri());
        assertThat(e.kind()).isEqualTo(Kind.RATE_LIMITED);
        assertThat(e.failure().httpStatus()).isEqualTo(429);
        assertThat(e.failure().retryAfter()).isEqualTo(Duration.ofSeconds(120));
    }

    @Test
    void rateLimitedWithRetryAfterHttpDate() {
        String date = DateTimeFormatter.RFC_1123_DATE_TIME.format(ZonedDateTime.ofInstant(NOW.plusSeconds(90),
                ZoneOffset.UTC));
        stub.handler(FeedStubServer.bytes(429, null, null, Map.of("Retry-After", date)));
        SourceException e = fail(client(), uri());
        assertThat(e.kind()).isEqualTo(Kind.RATE_LIMITED);
        assertThat(e.failure().retryAfter()).isEqualTo(Duration.ofSeconds(90));
    }

    @Test
    void retryAfterParsing() {
        assertThat(SourceHttpClient.parseRetryAfter(null, NOW)).isNull();
        assertThat(SourceHttpClient.parseRetryAfter("soon", NOW)).isNull();
        assertThat(SourceHttpClient.parseRetryAfter("0", NOW)).isEqualTo(Duration.ZERO);
        assertThat(SourceHttpClient.parseRetryAfter("99999999999", NOW)).isEqualTo(Duration.ofDays(7));
        assertThat(SourceHttpClient.parseRetryAfter("Wed, 07 Oct 2026 09:00:00 GMT", NOW)).isEqualTo(Duration.ZERO);
        assertThat(SourceHttpClient.parseRetryAfter("Wed, 07 Oct 2026 10:05:00 GMT", NOW))
                .isEqualTo(Duration.ofMinutes(5));
    }

    @Test
    void rateLimitedWithoutRetryAfter() {
        stub.handler(FeedStubServer.bytes(429, null, null, Map.of()));
        SourceException e = fail(client(), uri());
        assertThat(e.kind()).isEqualTo(Kind.RATE_LIMITED);
        assertThat(e.failure().retryAfter()).isNull();
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({
            "404, NOT_FOUND", "410, NOT_FOUND",
            "401, UNAUTHORIZED", "403, UNAUTHORIZED",
            "500, SERVER_ERROR", "502, SERVER_ERROR", "503, SERVER_ERROR",
            "400, INVALID_RESPONSE", "418, INVALID_RESPONSE", "422, INVALID_RESPONSE"})
    void statusMapping(int status, Kind kind) {
        stub.handler(FeedStubServer.bytes(status, "text/plain",
                ("error body SUPERSECRET123 " + status).getBytes(StandardCharsets.UTF_8), Map.of()));
        SourceException e = fail(client(), uri());
        assertThat(e.kind()).isEqualTo(kind);
        assertThat(e.failure().httpStatus()).isEqualTo(status);
        assertThat(e.getMessage()).contains(String.valueOf(status)).contains("/v1/boards/acme/jobs")
                .doesNotContain("error body");
    }

    @Test
    void serviceUnavailableCarriesRetryAfter() {
        stub.handler(FeedStubServer.bytes(503, null, null, Map.of("Retry-After", "30")));
        SourceException e = fail(client(), uri());
        assertThat(e.kind()).isEqualTo(Kind.SERVER_ERROR);
        assertThat(e.failure().retryAfter()).isEqualTo(Duration.ofSeconds(30));
    }

    // ---- timeouts and network ----

    @Test
    void readTimeoutIsTimeout() {
        stub.handler(exchange -> {
            Thread.sleep(4000);
            FeedStubServer.json(200, "{}").handle(exchange);
        });
        long start = System.nanoTime();
        SourceException e = fail(client(), uri());
        assertThat(e.kind()).isEqualTo(Kind.TIMEOUT);
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofMillis(3500));
    }

    @Test
    void slowDripBodyIsTimeout() {
        stub.handler(exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, 0);                 // chunked
            try (OutputStream os = exchange.getResponseBody()) {
                os.write('[');
                os.flush();
                for (int i = 0; i < 40; i++) {
                    Thread.sleep(200);
                    os.write(' ');
                    os.flush();
                }
                os.write(']');
            }
        });
        long start = System.nanoTime();
        SourceException e = fail(client(), uri());
        assertThat(e.kind()).isEqualTo(Kind.TIMEOUT);
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofMillis(4000));
    }

    @Test
    void connectionRefusedIsNetwork() throws IOException {
        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        URI closed = URI.create("http://127.0.0.1:" + port + "/v1/x?" + SECRET);
        SourceException e = fail(client(), closed);
        assertThat(e.kind()).isEqualTo(Kind.NETWORK);
    }

    // ---- body: gzip and cap ----

    private static byte[] gzip(byte[] plain) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(out)) {
            gz.write(plain);
        }
        return out.toByteArray();
    }

    @Test
    void gzipBodyIsDecodedAndHashedDecompressed() throws IOException {
        byte[] plain = "{\"jobs\":[{\"id\":1}]}".getBytes(StandardCharsets.UTF_8);
        stub.handler(FeedStubServer.bytes(200, "application/json", gzip(plain), Map.of("Content-Encoding", "gzip")));
        SourceResponse r = client(GENEROUS, 20_971_520L).get(uri(), FetchRequest.NONE);
        assertThat(r.body()).isEqualTo(plain);
        assertThat(r.bodyHash()).isEqualTo(SourceHttpClient.sha256(plain));
    }

    @Test
    void plainBodyOverCapIsTooLarge() {
        byte[] big = new byte[4096];
        Arrays.fill(big, (byte) ' ');
        stub.handler(FeedStubServer.bytes(200, "application/json", big, Map.of()));
        SourceException e = fail(client(1024), uri());
        assertThat(e.kind()).isEqualTo(Kind.TOO_LARGE);
    }

    @Test
    void bodyExactlyAtCapIsAccepted() {
        byte[] body = new byte[1024];
        Arrays.fill(body, (byte) ' ');
        stub.handler(FeedStubServer.bytes(200, "application/json", body, Map.of()));
        assertThat(client(1024).get(uri(), FetchRequest.NONE).body()).hasSize(1024);
    }

    @Test
    void decompressedBodyOverCapIsTooLarge() throws IOException {
        byte[] plain = new byte[64 * 1024];
        Arrays.fill(plain, (byte) ' ');
        byte[] compressed = gzip(plain);
        assertThat(compressed.length).isLessThan(1024);
        stub.handler(FeedStubServer.bytes(200, "application/json", compressed, Map.of("Content-Encoding", "gzip")));
        SourceException e = fail(client(1024), uri());
        assertThat(e.kind()).isEqualTo(Kind.TOO_LARGE);
    }

    @Test
    void chunkedBodyOverCapIsTooLarge() {
        stub.handler(exchange -> {
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream os = exchange.getResponseBody()) {
                for (int i = 0; i < 64; i++) {
                    os.write(new byte[512]);
                }
            }
        });
        SourceException e = fail(client(1024), uri());
        assertThat(e.kind()).isEqualTo(Kind.TOO_LARGE);
    }

    @Test
    void corruptGzipIsInvalidResponse() {
        stub.handler(FeedStubServer.bytes(200, "application/json",
                "definitely not gzip".getBytes(StandardCharsets.UTF_8), Map.of("Content-Encoding", "gzip")));
        SourceException e = fail(client(), uri());
        assertThat(e.kind()).isEqualTo(Kind.INVALID_RESPONSE);
    }

    @Test
    void unsupportedEncodingIsInvalidResponse() {
        stub.handler(FeedStubServer.bytes(200, "application/json", new byte[] {1, 2, 3}, Map.of("Content-Encoding", "br")));
        SourceException e = fail(client(), uri());
        assertThat(e.kind()).isEqualTo(Kind.INVALID_RESPONSE);
    }

    // ---- misc ----

    @Test
    void describeDropsQueryAndFragment() {
        assertThat(SourceHttpClient.describe(URI.create("https://api.example.com/v1/search/1?app_key=x&app_id=y#f")))
                .isEqualTo("api.example.com/v1/search/1");
        assertThat(SourceHttpClient.describe(URI.create("http://127.0.0.1:8080/p?q=1"))).isEqualTo("127.0.0.1:8080/p");
    }

    @Test
    void minHostSpacingDelaysTheSecondRequest() {
        SourceProperties props = new SourceProperties(5000,
                Fixtures.http(Duration.ofSeconds(1), Duration.ofSeconds(1), 1_000_000L, Duration.ofMillis(400)),
                null, null, null);
        SourceHttpClient c = new SourceHttpClient(props, Clock.systemUTC());
        stub.handler(FeedStubServer.json(200, "{}"));
        long start = System.nanoTime();
        c.get(uri(), FetchRequest.NONE);
        c.get(uri(), FetchRequest.NONE);
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isGreaterThanOrEqualTo(Duration.ofMillis(390));
    }
}
