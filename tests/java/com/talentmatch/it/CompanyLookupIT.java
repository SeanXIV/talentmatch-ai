package com.talentmatch.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.talentmatch.feed.source.BoardInfo;
import com.talentmatch.support.AbstractFeedIT;
import com.talentmatch.support.Api.Res;
import com.talentmatch.support.MutableClock;
import com.talentmatch.support.RouteStubServer.Captured;
import com.talentmatch.support.RouteStubServer.Reply;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Step 12: {@code POST /api/feed/sources/lookup} against the route stub. One dedicated context: a 3s
 * lookup budget (for the budget test) and a movable clock (the min-interval gate, 10s, is cleared before
 * each test by moving the clock forward).
 */
@ExtendWith(OutputCaptureExtension.class)
@Import(CompanyLookupIT.ClockConfig.class)
class CompanyLookupIT extends AbstractFeedIT {

    private static final String URL = "/api/feed/sources/lookup";
    private static final String NAME = "Acme Payments (Pty) Ltd";
    private static final List<String> SLUGS = List.of("acmepayments", "acme-payments", "acme");

    @TestConfiguration(proxyBeanMethods = false)
    static class ClockConfig {
        @Bean
        @Primary
        MutableClock lookupTestClock() {
            return MutableClock.ticking();
        }
    }

    @DynamicPropertySource
    static void lookupProperties(DynamicPropertyRegistry registry) {
        registry.add("talentmatch.feed.lookup.budget", () -> "3s");
        registry.add("talentmatch.feed.lookup.min-interval", () -> "10s");
        registry.add("talentmatch.feed.lookup.max-candidates", () -> "3");
    }

    @Autowired
    private MutableClock testClock;

    @BeforeEach
    void clearRateGate() {
        testClock.advance(Duration.ofMinutes(1));
    }

    // ------------------------------------------------------------------ helpers

    private static String gh(String slug) {
        return "/v1/boards/" + slug;
    }

    private static String lever(String slug) {
        return "/v0/postings/" + slug;
    }

    private static String ashby(String slug) {
        return "/posting-api/job-board/" + slug;
    }

    private Res lookup(String companyName) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("companyName", companyName);
        return api.post(URL, api.json(body));
    }

    private JsonNode ok(Res r) {
        assertThat(r.status()).as("lookup %s", r).isEqualTo(200);
        return r.json();
    }

    private static List<String> texts(JsonNode array) {
        List<String> out = new ArrayList<>();
        array.forEach(n -> out.add(n.asText()));
        return out;
    }

    private static List<String> keys(JsonNode array) {
        List<String> out = new ArrayList<>();
        array.forEach(n -> out.add(n.get("kind").asText() + ":" + n.get("boardToken").asText()));
        return out;
    }

    private static List<String> reasons(JsonNode unchecked) {
        List<String> out = new ArrayList<>();
        unchecked.forEach(n -> out.add(n.get("reason").asText()));
        return out;
    }

    private void assertNoAdzunaAndOnlyBoardChecks() {
        for (Captured c : STUB.requests()) {
            assertThat(c.path()).as("never Adzuna").doesNotStartWith("/v1/api/jobs");
            assertThat(c.method()).isEqualTo("GET");
        }
        assertThat(countRows("feed_api_usage")).isZero();
        assertThat(countRows("feed_source")).isZero();
    }

    // ------------------------------------------------------------------ happy path

    @Test
    void findsGreenhouseAndAshbyBoardsWithoutCreatingAnything() {
        STUB.route(gh("acmepayments"), Reply.json(200, "{\"name\":\"Acme Payments\",\"content\":\"<p>Hi</p>\"}"));
        STUB.route(ashby("acme"), Reply.json(200,
                "{\"jobs\":[{\"id\":\"1\",\"title\":\"Engineer\",\"isListed\":true},"
                        + "{\"id\":\"2\",\"title\":\"Hidden\",\"isListed\":false}]}"));

        JsonNode b = ok(lookup(NAME));
        assertThat(b.get("companyName").asText()).isEqualTo(NAME);
        assertThat(texts(b.get("candidates"))).isEqualTo(SLUGS);
        assertThat(b.get("complete").asBoolean()).isTrue();
        assertThat(b.get("unchecked").size()).isZero();
        assertThat(keys(b.get("found"))).containsExactly("GREENHOUSE:acmepayments", "ASHBY:acme");

        JsonNode g = b.get("found").get(0);
        assertThat(g.get("companyName").asText()).isEqualTo("Acme Payments");
        assertThat(g.get("openPostings").isNull()).isTrue();
        assertThat(g.get("warning").isNull()).isTrue();
        assertThat(g.get("sourceId").isNull()).isTrue();
        JsonNode a = b.get("found").get(1);
        assertThat(a.get("openPostings").asInt()).isEqualTo(1);
        assertThat(a.get("companyName").isNull()).isTrue();
        assertThat(a.get("sourceId").isNull()).isTrue();

        // all nine boards checked, slug-major, Greenhouse, Lever, Ashby
        List<String> expected = new ArrayList<>();
        for (String s : SLUGS) {
            expected.add(gh(s));
            expected.add(lever(s));
            expected.add(ashby(s));
        }
        assertThat(STUB.requests().stream().map(Captured::path).toList()).containsExactlyElementsOf(expected);
        Captured lev = STUB.requests(lever("acme")).get(0);
        assertThat(lev.uri().getQuery()).contains("mode=json").contains("limit=1");
        assertNoAdzunaAndOnlyBoardChecks();
    }

    @Test
    void emptyLeverBoardIsFoundWithZeroAndWarning() {
        STUB.route(lever("acme"), Reply.json(200, "[]"));
        JsonNode b = ok(lookup("Acme"));
        assertThat(texts(b.get("candidates"))).containsExactly("acme");
        assertThat(keys(b.get("found"))).containsExactly("LEVER:acme");
        JsonNode f = b.get("found").get(0);
        assertThat(f.get("openPostings").asInt()).isZero();
        assertThat(f.get("warning").asText()).isEqualTo(BoardInfo.NO_OPEN_POSTINGS);
        assertNoAdzunaAndOnlyBoardChecks();
    }

    @Test
    void nonEmptyLeverBoardHasUnknownCount() {
        STUB.route(lever("acme"), Reply.json(200, "[{\"id\":\"x\",\"text\":\"Engineer\"}]"));
        JsonNode f = ok(lookup("Acme")).get("found").get(0);
        assertThat(f.get("kind").asText()).isEqualTo("LEVER");
        assertThat(f.get("openPostings").isNull()).isTrue();
        assertThat(f.get("warning").isNull()).isTrue();
    }

    @Test
    void watchedBoardIsReportedFromTheDatabaseWithoutARequest() {
        UUID id = source("ASHBY", "acme", "Acme Corp", null);
        JsonNode b = ok(lookup("Acme"));
        assertThat(keys(b.get("found"))).containsExactly("ASHBY:acme");
        JsonNode f = b.get("found").get(0);
        assertThat(f.get("sourceId").asText()).isEqualTo(id.toString());
        assertThat(f.get("companyName").asText()).isEqualTo("Acme Corp");
        assertThat(f.get("warning").asText()).isEqualTo("Already on your watchlist.");
        assertThat(STUB.requests(ashby("acme"))).as("no request for the watched board").isEmpty();
        assertThat(STUB.requests(gh("acme"))).hasSize(1);
        assertThat(STUB.requests(lever("acme"))).hasSize(1);
        assertThat(countRows("feed_source")).isEqualTo(1);
    }

    @Test
    void providerFailureIsUncheckedWithReason() {
        STUB.route(gh("acme"), Reply.json(500, "{}"));
        STUB.route(lever("acme"), Reply.json(429, "{}"));
        JsonNode b = ok(lookup("Acme"));
        assertThat(keys(b.get("unchecked"))).containsExactly("GREENHOUSE:acme", "LEVER:acme");
        assertThat(reasons(b.get("unchecked"))).containsExactly("SERVER_ERROR", "RATE_LIMITED");
        assertThat(b.get("complete").asBoolean()).isTrue();
        assertThat(b.get("found").size()).isZero();
    }

    // ------------------------------------------------------------------ budget

    @Test
    void slowBoardTimesOutAndTheRestIsBudgetExceeded() {
        STUB.route(gh("acmepayments"), Reply.json(200, "{\"name\":\"Acme\"}").delayed(Duration.ofMillis(4_500)));
        // warm up the web stack so the first request of the context doesn't count against the timing
        assertThat(api.get("/api/feed/status").status()).isEqualTo(200);
        long start = System.nanoTime();
        JsonNode b = ok(lookup(NAME));
        long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        assertThat(ms).as("answers within the 3s budget plus slack").isBetween(2_500L, 5_000L);
        assertThat(b.get("complete").asBoolean()).isFalse();
        assertThat(b.get("found").size()).isZero();
        List<String> reasons = reasons(b.get("unchecked"));
        assertThat(reasons).hasSize(9);
        assertThat(reasons.get(0)).isEqualTo("TIMEOUT");
        assertThat(keys(b.get("unchecked")).get(0)).isEqualTo("GREENHOUSE:acmepayments");
        assertThat(reasons.subList(1, 9)).containsOnly("BUDGET_EXCEEDED");
        assertThat(STUB.requests()).extracting(Captured::path).containsOnly(gh("acmepayments"));
        assertNoAdzunaAndOnlyBoardChecks();
    }

    // ------------------------------------------------------------------ rate gate

    @Test
    void secondLookupWithinMinIntervalIs429ThenAllowedAfterIt() {
        ok(lookup("Acme"));
        int before = STUB.requests().size();

        Res r = lookup("Acme");
        JsonNode err = assertError(r, 429, "FEED_LOOKUP_RATE_LIMITED");
        assertThat(err.get("path").asText()).isEqualTo(URL);
        int retryAfter = Integer.parseInt(r.header("Retry-After"));
        assertThat(retryAfter).isBetween(1, 10);
        assertThat(err.get("message").asText()).contains("less than 10 seconds ago")
                .contains(retryAfter + " second");
        assertThat(STUB.requests()).as("no board checked when rate-limited").hasSize(before);

        testClock.advance(Duration.ofSeconds(retryAfter));
        ok(lookup("Acme"));
    }

    @Test
    void concurrentLookupIs429Busy() throws Exception {
        STUB.route(gh("acme"), Reply.json(200, "{\"name\":\"Acme\"}").delayed(Duration.ofMillis(1_500)));
        CompletableFuture<Res> first = CompletableFuture.supplyAsync(() -> lookup("Acme"));
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(5))
                .until(() -> !STUB.requests(gh("acme")).isEmpty());

        Res busy = lookup("Other Co");
        JsonNode err = assertError(busy, 429, "FEED_LOOKUP_RATE_LIMITED");
        assertThat(busy.header("Retry-After")).as("ceil(budget)").isEqualTo("3");
        assertThat(err.get("message").asText()).contains("Another company lookup is running");

        JsonNode b = ok(first.get(10, TimeUnit.SECONDS));
        assertThat(keys(b.get("found"))).containsExactly("GREENHOUSE:acme");
    }

    // ------------------------------------------------------------------ validation

    @Test
    void invalidRequestsAre400AndDontUseTheSlot() {
        Map<String, String> fe;
        fe = fieldMessages(assertError(api.post(URL, "{}"), 400, "VALIDATION_FAILED"));
        assertThat(fe).containsOnlyKeys("companyName");
        fe = fieldMessages(assertError(lookup("   "), 400, "VALIDATION_FAILED"));
        assertThat(fe).containsOnlyKeys("companyName");
        fe = fieldMessages(assertError(lookup("x".repeat(201)), 400, "VALIDATION_FAILED"));
        assertThat(fe.get("companyName")).contains("200");
        fe = fieldMessages(assertError(lookup("Яндекс"), 400, "VALIDATION_FAILED"));
        assertThat(fe).containsOnlyKeys("companyName");
        assertThat(fe.get("companyName")).isEqualTo("companyName has no letters or digits (a\u2013z, 0\u20139) to "
                + "build a board name from. Add the source with the boardToken from its board URL instead.");
        fe = fieldMessages(assertError(lookup("a".repeat(150)), 400, "VALIDATION_FAILED"));
        assertThat(fe).containsOnlyKeys("companyName");
        assertThat(fe.get("companyName")).isEqualTo("companyName gives no usable board name (board names are at "
                + "most 100 characters of a\u2013z, 0\u20139 and -). Add the source with the boardToken from its "
                + "board URL instead.");
        assertError(api.post(URL, "{\"companyName\":\"Acme\",\"kind\":\"LEVER\"}"), 400, "MALFORMED_REQUEST");
        assertError(api.post(URL, "{\"companyName\":"), 400, "MALFORMED_REQUEST");
        assertThat(STUB.requests()).isEmpty();

        // none of them took the lookup slot or started the interval
        ok(lookup("Acme"));
    }

    // ------------------------------------------------------------------ logs

    @Test
    void logsNeverContainTheCompanyNameOrBoardNames(CapturedOutput output) {
        String name = "Zebracorn Quokkapay Ltd";
        STUB.route(gh("zebracornquokkapay"), Reply.json(200, "{\"name\":\"Zebracorn Quokkapay\"}"));
        STUB.route(lever("zebracorn-quokkapay"), Reply.json(500, "{}"));
        STUB.route(ashby("zebracorn"), Reply.json(503, "{}"));
        JsonNode b = ok(lookup(name));
        assertThat(b.get("found").size()).isEqualTo(1);
        assertThat(b.get("unchecked").size()).isEqualTo(2);
        // rate-limited and invalid requests also log
        assertError(lookup(name), 429, "FEED_LOOKUP_RATE_LIMITED");
        assertError(lookup("Zebracorn" + "q".repeat(200)), 400, "VALIDATION_FAILED");

        String logs = output.getAll();
        assertThat(logs).contains("Feed lookup candidates=3 probes=9 found=1 unchecked=2 complete=true");
        assertThat(logs.toLowerCase()).doesNotContain("zebracorn").doesNotContain("quokkapay");
        // probe failures log the failure kind and status only - no stub host:port, URL or request path
        List<String> probeFailures = logs.lines()
                .filter(l -> l.contains("Feed probe kind=") && l.contains(" failed")).toList();
        assertThat(probeFailures).hasSize(2).allSatisfy(l -> assertThat(l).contains("failure=")
                .contains("httpStatus=").doesNotContain("unexpectedly"));
        String hostPort = java.net.URI.create(STUB.baseUrl()).getAuthority();
        assertThat(logs).doesNotContain(hostPort).doesNotContain(STUB.baseUrl());
        assertThat(probeFailures).allSatisfy(l -> assertThat(l)
                .doesNotContain("/v1/boards").doesNotContain("/v0/postings").doesNotContain("/posting-api")
                .doesNotContain("http://"));
    }
}
