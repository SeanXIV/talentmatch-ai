package com.talentmatch.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.talentmatch.feed.source.BoardInfo;
import com.talentmatch.support.AbstractApiIT;
import com.talentmatch.support.Api.Res;
import com.talentmatch.support.RouteStubServer;
import com.talentmatch.support.RouteStubServer.Reply;
import java.time.Duration;
import java.time.Instant;
import java.sql.Timestamp;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** §9.2 item 2 / §5.1: source CRUD with the board check hitting a local stub. */
class FeedSourceIT extends AbstractApiIT {

    private static final String URL = "/api/feed/sources";
    private static final RouteStubServer STUB = new RouteStubServer();
    private static final RouteStubServer EU_STUB = new RouteStubServer();
    private static final String HASH = "a".repeat(64);

    @DynamicPropertySource
    static void feedProperties(DynamicPropertyRegistry registry) {
        registry.add("talentmatch.feed.greenhouse.base-url", STUB::baseUrl);
        registry.add("talentmatch.feed.lever.base-url", STUB::baseUrl);
        registry.add("talentmatch.feed.lever.eu-base-url", EU_STUB::baseUrl);
        registry.add("talentmatch.feed.ashby.base-url", STUB::baseUrl);
        registry.add("talentmatch.feed.probe-timeout", () -> "2s");
        registry.add("talentmatch.feed.http.read-timeout", () -> "10s");
        registry.add("talentmatch.feed.http.min-host-spacing", () -> "0s");
    }

    @BeforeEach
    void resetStubs() {
        STUB.reset();
        EU_STUB.reset();
    }

    // ------------------------------------------------------------------ helpers

    private static String gh(String token) {
        return "/v1/boards/" + token;
    }

    private static String lever(String token) {
        return "/v0/postings/" + token;
    }

    private static String ashby(String token) {
        return "/posting-api/job-board/" + token;
    }

    private Res post(Map<String, Object> body) {
        return api.post(URL, api.json(body));
    }

    private Res post(String kind, String token) {
        return post(body(kind, token));
    }

    private static Map<String, Object> body(String kind, String token) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("kind", kind);
        m.put("boardToken", token);
        return m;
    }

    private UUID created(Res r) {
        assertThat(r.status()).as("POST %s", r).isEqualTo(201);
        return UUID.fromString(r.json().get("id").asText());
    }

    private UUID unverified(String kind, String token) {
        Map<String, Object> b = body(kind, token);
        b.put("verify", false);
        return created(post(b));
    }

    private int sourceCount() {
        return jdbc.queryForObject("SELECT count(*) FROM feed_source", Integer.class);
    }

    private static List<String> toList(JsonNode array) {
        List<String> out = new java.util.ArrayList<>();
        array.forEach(n -> out.add(n.asText()));
        return out;
    }

    // ------------------------------------------------------------------ POST: created

    @Test
    void greenhouseCreatedWithBoardName() {
        STUB.route(gh("acme"), Reply.json(200, "{\"name\":\"Acme Corp\",\"content\":\"<p>x</p>\"}"));
        Res r = post("GREENHOUSE", "acme");
        UUID id = created(r);
        JsonNode b = r.json();
        assertThat(r.header("Location")).endsWith("/api/feed/sources/" + id);
        assertThat(b.get("kind").asText()).isEqualTo("GREENHOUSE");
        assertThat(b.get("managedBy").asText()).isEqualTo("OWNER");
        assertThat(b.get("state").asText()).isEqualTo("ACTIVE");
        assertThat(b.get("companyName").asText()).isEqualTo("Acme Corp");
        assertThat(b.get("boardToken").asText()).isEqualTo("acme");
        assertThat(b.get("options").size()).isZero();
        assertThat(b.get("pollIntervalSeconds").isNull()).isTrue();
        assertThat(b.get("effectivePollIntervalSeconds").asInt()).isEqualTo(300);
        assertThat(b.get("warnings").isArray()).isTrue();
        assertThat(b.get("warnings").size()).isZero();
        assertThat(b.get("consecutiveFailures").asInt()).isZero();
        assertThat(b.get("openPostings").asInt()).isZero();
        assertThat(b.has("etag")).isFalse();
        assertThat(b.has("contentHash")).isFalse();
        assertThat(b.has("sourceKey")).isFalse();
        assertThat(STUB.requests(gh("acme"))).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT source_key FROM feed_source WHERE id = ?", String.class, id))
                .isEqualTo("greenhouse:acme");

        Res get = api.get(URL + "/" + id);
        assertThat(get.status()).isEqualTo(200);
        assertThat(get.json().get("companyName").asText()).isEqualTo("Acme Corp");
        assertThat(get.json().get("warnings").size()).isZero();
    }

    @Test
    void ownerCompanyNameWinsOverBoardName() {
        STUB.route(gh("acme"), Reply.json(200, "{\"name\":\"Acme Corp\"}"));
        Map<String, Object> b = body("GREENHOUSE", "acme");
        b.put("companyName", "  My   Acme ");
        assertThat(post(b).json().get("companyName").asText()).isEqualTo("My Acme");
    }

    @Test
    void leverCreated() {
        STUB.route(lever("acme"), Reply.json(200, "[{\"id\":\"p1\",\"text\":\"Engineer\"}]"));
        Map<String, Object> b = body("LEVER", "acme");
        b.put("pollIntervalSeconds", 600);
        Res r = post(b);
        UUID id = created(r);
        assertThat(r.header("Location")).endsWith("/api/feed/sources/" + id);
        assertThat(r.json().get("pollIntervalSeconds").asInt()).isEqualTo(600);
        assertThat(r.json().get("effectivePollIntervalSeconds").asInt()).isEqualTo(600);
        assertThat(r.json().get("companyName").isNull()).isTrue();
        assertThat(r.json().get("warnings").size()).isZero();
        assertThat(STUB.requests(lever("acme"))).singleElement()
                .satisfies(c -> assertThat(c.uri().getQuery()).contains("mode=json"));
        assertThat(EU_STUB.requests()).isEmpty();
    }

    @Test
    void ashbyCreated() {
        STUB.route(ashby("acme"), Reply.json(200, "{\"apiVersion\":\"1\",\"jobs\":[{\"id\":\"1\",\"title\":\"E\","
                + "\"isListed\":true}]}"));
        Res r = post("ASHBY", "acme");
        created(r);
        assertThat(r.header("Location")).endsWith("/api/feed/sources/" + r.json().get("id").asText());
        assertThat(r.json().get("effectivePollIntervalSeconds").asInt()).isEqualTo(300);
        assertThat(r.json().get("warnings").size()).isZero();
    }

    @Test
    void leverEmptyBoardWarnsNoOpenPostings() {
        STUB.route(lever("acme"), Reply.json(200, "[]"));
        Res r = post("LEVER", "acme");
        created(r);
        assertThat(toList(r.json().get("warnings"))).containsExactly(BoardInfo.NO_OPEN_POSTINGS);
    }

    @Test
    void ashbyWithNoListedJobsWarnsNoOpenPostings() {
        STUB.route(ashby("acme"), Reply.json(200, "{\"jobs\":[{\"id\":\"1\",\"title\":\"E\",\"isListed\":false}]}"));
        Res r = post("ASHBY", "acme");
        created(r);
        assertThat(toList(r.json().get("warnings"))).containsExactly(BoardInfo.NO_OPEN_POSTINGS);
    }

    // ------------------------------------------------------------------ POST: board not found

    @Test
    void greenhouse404Is400OnBoardTokenAndNothingInserted() {
        STUB.route(gh("nope"), Reply.json(404, "{\"status\":404,\"error\":\"Job not found\"}"));
        JsonNode err = assertError(post("GREENHOUSE", "nope"), 400, "VALIDATION_FAILED");
        assertThat(fieldMessages(err)).containsOnlyKeys("boardToken");
        assertThat(fieldMessages(err).get("boardToken")).isEqualTo("Greenhouse has no job board 'nope'. Check the "
                + "token in the board URL (boards.greenhouse.io/<token>).");
        assertThat(sourceCount()).isZero();
    }

    @Test
    void ashbyTextPlain404Is400() {
        STUB.route(ashby("nope"), Reply.text(404, "Not Found"));
        JsonNode err = assertError(post("ASHBY", "nope"), 400, "VALIDATION_FAILED");
        assertThat(fields(err)).containsExactly("boardToken");
        assertThat(fieldMessages(err).get("boardToken")).contains("Ashby has no job board 'nope'");
        assertThat(sourceCount()).isZero();
    }

    @Test
    void lever404Is400() {
        // unrouted path: the stub answers 404 text/plain
        JsonNode err = assertError(post("LEVER", "Nope"), 400, "VALIDATION_FAILED");
        assertThat(fieldMessages(err).get("boardToken")).contains("Lever has no job site 'Nope'")
                .contains("case-sensitive");
        assertThat(sourceCount()).isZero();
    }

    // ------------------------------------------------------------------ POST: board not checked

    @Test
    void stub503IsCreatedWithWarning() {
        STUB.route(gh("acme"), Reply.json(503, "{}"));
        Res r = post("GREENHOUSE", "acme");
        created(r);
        assertThat(toList(r.json().get("warnings"))).containsExactly(
                "Couldn't reach Greenhouse to check the board; it will be checked on the first poll.");
        assertThat(r.json().get("companyName").isNull()).isTrue();
        assertThat(sourceCount()).isEqualTo(1);
    }

    @Test
    void slowBoardPastProbeTimeoutIsCreatedWithWarning() {
        STUB.route(lever("slow"), Reply.json(200, "[]").delayed(Duration.ofSeconds(6)));
        long start = System.nanoTime();
        Res r = post("LEVER", "slow");
        long ms = Duration.ofNanos(System.nanoTime() - start).toMillis();
        created(r);
        assertThat(ms).as("returned within about the 2s probe timeout").isBetween(1_800L, 4_500L);
        assertThat(toList(r.json().get("warnings"))).singleElement().asString().contains("Couldn't reach Lever");
    }

    @Test
    void verifyFalseMakesNoCall() {
        Map<String, Object> b = body("GREENHOUSE", "acme");
        b.put("verify", false);
        Res r = post(b);
        created(r);
        assertThat(STUB.requests()).isEmpty();
        assertThat(toList(r.json().get("warnings"))).singleElement().asString().contains("verify=false");
    }

    // ------------------------------------------------------------------ POST: duplicates and Lever EU

    @Test
    void greenhouseDuplicateIsCaseInsensitive409() {
        STUB.route(gh("acme"), Reply.json(200, "{\"name\":\"Acme\"}"));
        STUB.route(gh("Acme"), Reply.json(200, "{\"name\":\"Acme\"}"));
        UUID first = created(post("GREENHOUSE", "acme"));
        JsonNode err = assertError(post("GREENHOUSE", "Acme"), 409, "FEED_SOURCE_ALREADY_EXISTS");
        assertThat(err.get("message").asText()).contains(first.toString());
        assertThat(STUB.requests(gh("Acme"))).as("no probe for a duplicate").isEmpty();
        assertThat(sourceCount()).isEqualTo(1);
        // the same token again
        assertError(post("GREENHOUSE", "acme"), 409, "FEED_SOURCE_ALREADY_EXISTS");
    }

    @Test
    void leverTokensAreCaseSensitive() {
        STUB.route(lever("Acme"), Reply.json(200, "[{\"id\":\"1\"}]"));
        STUB.route(lever("acme"), Reply.json(200, "[{\"id\":\"1\"}]"));
        created(post("LEVER", "Acme"));
        created(post("LEVER", "acme"));
        assertThat(jdbc.queryForList("SELECT source_key FROM feed_source ORDER BY source_key", String.class))
                .containsExactlyInAnyOrder("lever:Acme", "lever:acme");
    }

    @Test
    void leverEuUsesEuBaseUrl() {
        EU_STUB.route(lever("acme"), Reply.json(200, "[{\"id\":\"1\"}]"));
        STUB.route(lever("acme"), Reply.json(200, "[{\"id\":\"1\"}]"));
        Map<String, Object> b = body("LEVER", "acme");
        b.put("options", Map.of("leverInstance", "eu"));
        Res r = post(b);
        UUID id = created(r);
        assertThat(EU_STUB.requests(lever("acme"))).hasSize(1);
        assertThat(STUB.requests()).isEmpty();
        assertThat(r.json().get("options").get("leverInstance").asText()).isEqualTo("eu");
        assertThat(jdbc.queryForObject("SELECT source_key FROM feed_source WHERE id = ?", String.class, id))
                .isEqualTo("lever:eu:acme");
        // the global site with the same name is a different source
        created(post("LEVER", "acme"));
        assertThat(STUB.requests(lever("acme"))).hasSize(1);
        // EU again (any case of the option) is a duplicate
        Map<String, Object> again = body("LEVER", "acme");
        again.put("options", Map.of("leverInstance", "EU"));
        assertError(post(again), 409, "FEED_SOURCE_ALREADY_EXISTS");
    }

    @Test
    void euNotFoundMentionsInstance() {
        Map<String, Object> b = body("LEVER", "acme");
        b.put("options", Map.of("leverInstance", "eu"));
        JsonNode err = assertError(post(b), 400, "VALIDATION_FAILED");
        assertThat(fieldMessages(err).get("boardToken")).contains("EU instance");
    }

    // ------------------------------------------------------------------ POST: bad requests

    @Test
    void adzunaIs400OnKind() {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("kind", "ADZUNA");
        b.put("options", Map.of("what", "java"));
        JsonNode err = assertError(post(b), 400, "VALIDATION_FAILED");
        assertThat(fields(err)).containsExactly("kind");
        assertThat(STUB.requests()).isEmpty();
        assertThat(sourceCount()).isZero();
    }

    @Test
    void validationErrors() {
        assertThat(fields(assertError(api.post(URL, "{}"), 400, "VALIDATION_FAILED"))).containsExactly("kind");
        assertThat(fields(assertError(post("GREENHOUSE", "a b"), 400, "VALIDATION_FAILED")))
                .containsExactly("boardToken");
        assertThat(fields(assertError(post("GREENHOUSE", null), 400, "VALIDATION_FAILED")))
                .containsExactly("boardToken");
        Map<String, Object> b = body("GREENHOUSE", "acme");
        b.put("pollIntervalSeconds", 119);
        b.put("companyName", "n".repeat(201));
        b.put("options", Map.of("leverInstance", "eu"));
        assertThat(fields(assertError(post(b), 400, "VALIDATION_FAILED")))
                .containsExactly("companyName", "options.leverInstance", "pollIntervalSeconds");
        Map<String, Object> l = body("LEVER", "acme");
        l.put("options", Map.of("leverInstance", "mars"));
        assertThat(fields(assertError(post(l), 400, "VALIDATION_FAILED"))).containsExactly("options.leverInstance");
        assertThat(STUB.requests()).isEmpty();
    }

    @Test
    void malformedRequests() {
        assertError(api.post(URL, "{\"kind\":\"GREENHOUSE\",\"boardToken\":\"acme\",\"surprise\":1}"), 400,
                "MALFORMED_REQUEST");
        assertError(api.post(URL, "{\"kind\":\"WORKDAY\",\"boardToken\":\"acme\"}"), 400, "MALFORMED_REQUEST");
        assertError(api.post(URL, "{\"kind\":\"LEVER\",\"boardToken\":\"acme\",\"options\":{\"region\":\"eu\"}}"),
                400, "MALFORMED_REQUEST");
        assertError(api.post(URL, "{\"kind\":\"LEVER\",\"boardToken\":\"acme\",\"verify\":\"yes\"}"), 400,
                "MALFORMED_REQUEST");
        assertError(api.post(URL, "{not json"), 400, "MALFORMED_REQUEST");
        assertThat(sourceCount()).isZero();
    }

    // ------------------------------------------------------------------ GET

    @Test
    void listPagingAndFilters() {
        UUID a = unverified("GREENHOUSE", "a");
        unverified("GREENHOUSE", "b");
        UUID c = unverified("LEVER", "c");
        jdbc.update("UPDATE feed_source SET state = 'PAUSED' WHERE id = ?", a);

        JsonNode page0 = api.get(URL + "?size=2").json();
        assertThat(page0.get("totalElements").asLong()).isEqualTo(3);
        assertThat(page0.get("totalPages").asInt()).isEqualTo(2);
        assertThat(page0.get("content").size()).isEqualTo(2);
        assertThat(page0.get("content").get(0).get("warnings").size()).isZero();
        JsonNode page1 = api.get(URL + "?size=2&page=1").json();
        assertThat(page1.get("content").size()).isEqualTo(1);

        JsonNode levers = api.get(URL + "?kind=LEVER").json();
        assertThat(levers.get("totalElements").asLong()).isEqualTo(1);
        assertThat(levers.get("content").get(0).get("id").asText()).isEqualTo(c.toString());

        JsonNode paused = api.get(URL + "?state=PAUSED").json();
        assertThat(paused.get("totalElements").asLong()).isEqualTo(1);
        assertThat(paused.get("content").get(0).get("id").asText()).isEqualTo(a.toString());

        assertThat(api.get(URL + "?kind=GREENHOUSE&state=ACTIVE").json().get("totalElements").asLong()).isEqualTo(1);
        assertThat(api.get(URL + "?kind=ASHBY").json().get("totalElements").asLong()).isZero();
        assertThat(api.get(URL + "?kind=ASHBY").json().get("content").size()).isZero();
    }

    @Test
    void listBadParameters() {
        assertError(api.get(URL + "?kind=WORKDAY"), 400, "INVALID_PARAMETER");
        assertError(api.get(URL + "?state=SLEEPING"), 400, "INVALID_PARAMETER");
        assertError(api.get(URL + "?size=0"), 400, "INVALID_PARAMETER");
        assertError(api.get(URL + "?page=-1"), 400, "INVALID_PARAMETER");
        assertError(api.get(URL + "?size=abc"), 400, "INVALID_PARAMETER");
    }

    @Test
    void getById() {
        assertError(api.get(URL + "/" + UUID.randomUUID()), 404, "FEED_SOURCE_NOT_FOUND");
        assertError(api.get(URL + "/not-a-uuid"), 400, "INVALID_ID");
        assertError(api.put(URL + "/not-a-uuid", "{\"state\":\"ACTIVE\"}"), 400, "INVALID_ID");
        assertError(api.delete(URL + "/not-a-uuid"), 400, "INVALID_ID");
    }

    // ------------------------------------------------------------------ PUT

    @Test
    void putPausePersists() {
        UUID id = unverified("GREENHOUSE", "acme");
        Res r = api.put(URL + "/" + id, "{\"companyName\":\" Acme \",\"state\":\"PAUSED\",\"pollIntervalSeconds\":900}");
        assertThat(r.status()).as("%s", r).isEqualTo(200);
        assertThat(r.json().get("state").asText()).isEqualTo("PAUSED");
        assertThat(r.json().get("companyName").asText()).isEqualTo("Acme");
        assertThat(r.json().get("effectivePollIntervalSeconds").asInt()).isEqualTo(900);
        assertThat(r.json().get("warnings").size()).isZero();
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT state, company_name, poll_interval_seconds FROM feed_source WHERE id = ?", id);
        assertThat(row).containsEntry("state", "PAUSED").containsEntry("company_name", "Acme")
                .containsEntry("poll_interval_seconds", 900);

        // full replace: omitted name and interval are cleared
        Res cleared = api.put(URL + "/" + id, "{\"state\":\"PAUSED\"}");
        assertThat(cleared.status()).isEqualTo(200);
        assertThat(cleared.json().get("companyName").isNull()).isTrue();
        assertThat(cleared.json().get("effectivePollIntervalSeconds").asInt()).isEqualTo(300);
    }

    @Test
    void putResumeMakesDueNow() {
        UUID id = unverified("LEVER", "acme");
        jdbc.update("UPDATE feed_source SET state = 'PAUSED', next_poll_at = now() + interval '1 day' WHERE id = ?",
                id);
        Instant before = Instant.now().minusSeconds(5);
        assertThat(api.put(URL + "/" + id, "{\"state\":\"ACTIVE\"}").status()).isEqualTo(200);
        Instant next = jdbc.queryForObject("SELECT next_poll_at FROM feed_source WHERE id = ?", Timestamp.class, id)
                .toInstant();
        assertThat(next).isBetween(before, Instant.now().plusSeconds(5));
    }

    @Test
    void putErrors() {
        UUID id = unverified("GREENHOUSE", "acme");
        assertError(api.put(URL + "/" + id, "{\"state\":\"PAUSED\",\"kind\":\"LEVER\"}"), 400, "MALFORMED_REQUEST");
        assertError(api.put(URL + "/" + id, "{\"state\":\"PAUSED\",\"boardToken\":\"x\"}"), 400,
                "MALFORMED_REQUEST");
        assertError(api.put(URL + "/" + id, "{\"state\":\"GONE\"}"), 400, "MALFORMED_REQUEST");
        assertThat(fields(assertError(api.put(URL + "/" + id, "{\"companyName\":\"x\"}"), 400,
                "VALIDATION_FAILED"))).containsExactly("state");
        assertThat(fields(assertError(api.put(URL + "/" + id, "{\"state\":\"ACTIVE\",\"pollIntervalSeconds\":86401}"),
                400, "VALIDATION_FAILED"))).containsExactly("pollIntervalSeconds");
        assertError(api.put(URL + "/" + UUID.randomUUID(), "{\"state\":\"ACTIVE\"}"), 404, "FEED_SOURCE_NOT_FOUND");
        assertThat(jdbc.queryForObject("SELECT state FROM feed_source WHERE id = ?", String.class, id))
                .isEqualTo("ACTIVE");
    }

    @Test
    void preferencesManagedSourceIsReadOnly() {
        UUID id = jdbc.queryForObject("INSERT INTO feed_source (source_key, kind, managed_by, options) "
                + "VALUES ('adzuna:za:abc', 'ADZUNA', 'PREFERENCES', '{\"country\":\"za\",\"what\":\"java\"}') "
                + "RETURNING id", UUID.class);
        Res get = api.get(URL + "/" + id);
        assertThat(get.status()).isEqualTo(200);
        assertThat(get.json().get("managedBy").asText()).isEqualTo("PREFERENCES");
        assertThat(get.json().get("effectivePollIntervalSeconds").asInt()).isEqualTo(900);

        JsonNode put = assertError(api.put(URL + "/" + id, "{\"state\":\"PAUSED\"}"), 409, "DATA_CONFLICT");
        assertThat(put.get("message").asText()).contains("managed by your job preferences");
        assertError(api.delete(URL + "/" + id), 409, "DATA_CONFLICT");
        assertThat(jdbc.queryForObject("SELECT state FROM feed_source WHERE id = ?", String.class, id))
                .isEqualTo("ACTIVE");
    }

    // ------------------------------------------------------------------ DELETE

    private UUID feedJob(String title) {
        UUID id = jdbc.queryForObject("INSERT INTO job (title, company, origin) VALUES (?, 'Acme', 'FEED') "
                + "RETURNING id", UUID.class, title);
        jdbc.update("INSERT INTO feed_job (job_id, dedup_key, primary_url) VALUES (?, ?, 'https://x.test/j')", id,
                "acme|" + title);
        return id;
    }

    private void posting(UUID sourceId, UUID jobId, String externalId) {
        jdbc.update("INSERT INTO job_posting (source_id, external_id, job_id, url, title, company, content_hash) "
                + "VALUES (?, ?, ?, 'https://x.test/p', 'T', 'Acme', ?)", sourceId, externalId, jobId, HASH);
    }

    private int count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }

    @Test
    void deleteRemovesPostingsAndOrphanedFeedJobsOnly() {
        UUID java = skill("Java");
        UUID cand = candidate("Ann", "ann@example.com", "Java");
        UUID a = unverified("GREENHOUSE", "acme");
        UUID b = unverified("LEVER", "acme");

        UUID onlyA = feedJob("Only A");
        posting(a, onlyA, "a1");
        UUID shared = feedJob("Shared");
        posting(a, shared, "a2");
        posting(b, shared, "b1");
        for (UUID job : List.of(onlyA, shared)) {
            jdbc.update("INSERT INTO job_skill (job_id, skill_id, required) VALUES (?, ?, true)", job, java);
            jdbc.update("INSERT INTO job_match (candidate_id, job_id, score) VALUES (?, ?, 1.0)", cand, job);
        }
        jdbc.update("INSERT INTO feed_notification (job_id, channel, score) VALUES (?, 'EMAIL', 0.8)", onlyA);
        UUID manual = job("Manual Engineer", "Acme", "Java");
        int manualMatches = countMatchRows(manual);

        Res del = api.delete(URL + "/" + a);
        assertThat(del.status()).as("%s", del).isEqualTo(204);
        assertThat(del.body()).isNullOrEmpty();

        assertThat(count("SELECT count(*) FROM feed_source WHERE id = ?", a)).isZero();
        assertThat(count("SELECT count(*) FROM job_posting WHERE source_id = ?", a)).isZero();
        // the orphaned feed job and everything hanging off it
        assertThat(count("SELECT count(*) FROM job WHERE id = ?", onlyA)).isZero();
        assertThat(count("SELECT count(*) FROM feed_job WHERE job_id = ?", onlyA)).isZero();
        assertThat(count("SELECT count(*) FROM job_skill WHERE job_id = ?", onlyA)).isZero();
        assertThat(count("SELECT count(*) FROM job_match WHERE job_id = ?", onlyA)).isZero();
        assertThat(count("SELECT count(*) FROM feed_notification WHERE job_id = ?", onlyA)).isZero();
        // the job still posted by source b survives with its rows
        assertThat(count("SELECT count(*) FROM job WHERE id = ?", shared)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM feed_job WHERE job_id = ?", shared)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM job_posting WHERE job_id = ?", shared)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM job_skill WHERE job_id = ?", shared)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM job_match WHERE job_id = ?", shared)).isEqualTo(1);
        // manual jobs untouched
        assertThat(count("SELECT count(*) FROM job WHERE id = ? AND origin = 'MANUAL'", manual)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM job_skill WHERE job_id = ?", manual)).isEqualTo(1);
        assertThat(countMatchRows(manual)).isEqualTo(manualMatches);
        // the other source untouched
        assertThat(count("SELECT count(*) FROM feed_source WHERE id = ?", b)).isEqualTo(1);

        assertError(api.delete(URL + "/" + a), 404, "FEED_SOURCE_NOT_FOUND");
        assertError(api.get(URL + "/" + a), 404, "FEED_SOURCE_NOT_FOUND");

        // deleting b now orphans the shared job
        assertThat(api.delete(URL + "/" + b).status()).isEqualTo(204);
        assertThat(count("SELECT count(*) FROM job WHERE origin = 'FEED'")).isZero();
        assertThat(count("SELECT count(*) FROM job WHERE origin = 'MANUAL'")).isEqualTo(1);
    }
}
