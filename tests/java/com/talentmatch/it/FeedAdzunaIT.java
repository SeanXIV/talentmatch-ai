package com.talentmatch.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.talentmatch.feed.AggregatorQueries;
import com.talentmatch.feed.PollOutcome;
import com.talentmatch.support.AbstractFeedIT;
import com.talentmatch.support.Api.Res;
import com.talentmatch.support.RouteStubServer.Reply;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Step 9 (decision c): Adzuna searches against the stub, with keys configured and a daily budget of 3.
 * §9.2 items 5 (dedup with a company board), 9 (budget) and 12 (derived searches reconciled), plus
 * owner-added searches, closing after {@code unseen-close-after}, page 2 and the status block.
 */
@ExtendWith(OutputCaptureExtension.class)
class FeedAdzunaIT extends AbstractFeedIT {

    private static final String KEY = "IT-SECRET-ADZUNA-KEY";
    private static final String PAGE1 = "/v1/api/jobs/za/search/1";
    private static final String PAGE2 = "/v1/api/jobs/za/search/2";

    @DynamicPropertySource
    static void adzunaProperties(DynamicPropertyRegistry registry) {
        registry.add("talentmatch.feed.adzuna.app-id", () -> "it-app");
        registry.add("talentmatch.feed.adzuna.app-key", () -> KEY);
        registry.add("talentmatch.feed.adzuna.daily-request-budget", () -> "3");
        registry.add("talentmatch.feed.adzuna.results-per-page", () -> "2");
    }

    @Autowired
    private AggregatorQueries aggregatorQueries;

    private final Instant start = Instant.now().truncatedTo(ChronoUnit.SECONDS);

    private record Ad(String id, String title, String company, Instant created) {
    }

    private String body(Ad... ads) {
        List<Map<String, Object>> results = new ArrayList<>();
        for (Ad a : ads) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", a.id());
            m.put("title", a.title());
            m.put("description", "Snippet about <strong>Java</strong> and PostgreSQL for " + a.id());
            m.put("created", a.created().toString());
            m.put("redirect_url", "https://www.adzuna.co.za/land/ad/" + a.id() + "?se=it");
            m.put("company", Map.of("display_name", a.company()));
            m.put("location", Map.of("display_name", "Cape Town, Western Cape"));
            m.put("salary_min", 600000);
            m.put("salary_is_predicted", "1");
            results.add(m);
        }
        return api.json(Map.of("count", ads.length, "results", results));
    }

    private void adzunaReplies(Ad... ads) {
        STUB.route(PAGE1, Reply.json(200, body(ads)));
    }

    private UUID adzunaSearch(String what) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("kind", "ADZUNA");
        body.put("options", Map.of("what", what));
        body.put("verify", false);
        Res r = api.post("/api/feed/sources", api.json(body));
        assertThat(r.status()).as("POST %s", r).isEqualTo(201);
        return UUID.fromString(r.json().get("id").asText());
    }

    private int requestsToday() {
        List<Integer> n = jdbc.queryForList("SELECT requests FROM feed_api_usage WHERE provider = 'ADZUNA' AND day = ?",
                Integer.class, java.sql.Date.valueOf(LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC)));
        return n.isEmpty() ? 0 : n.get(0);
    }

    // ------------------------------------------------------------------ owner-added search

    @Test
    void ownerSearchIsPolledWithKeysInTheQueryButNotInTheLogs(CapturedOutput out) {
        UUID s = adzunaSearch("java developer");
        JsonNode view = api.get("/api/feed/sources/" + s).json();
        assertThat(view.get("kind").asText()).isEqualTo("ADZUNA");
        assertThat(view.get("boardToken").isNull()).isTrue();
        assertThat(view.get("options").get("country").asText()).isEqualTo("za");
        assertThat(view.get("options").get("what").asText()).isEqualTo("java developer");

        adzunaReplies(new Ad("a1", "Java Developer", "Acme", start.minus(Duration.ofHours(1))));
        PollOutcome.Ok ok = pollOk(s);
        assertThat(ok.stats().created()).isEqualTo(1);
        assertThat(STUB.requests(PAGE1)).singleElement().satisfies(c -> assertThat(c.uri().getRawQuery())
                .contains("app_key=" + KEY, "what=java+developer", "sort_by=date"));

        Map<String, Object> posting = posting(s, "a1");
        assertThat(posting.get("salary_estimated")).isEqualTo(true);
        assertThat(posting.get("salary_currency")).isEqualTo("ZAR");
        assertThat(requestsToday()).isEqualTo(1);
        assertThat(out.getAll()).doesNotContain(KEY);

        JsonNode job = api.get("/api/feed/jobs/" + jobOf(s, "a1")).json();
        assertThat(job.get("sources").get(0).get("via").asText()).isEqualTo("Adzuna");
        assertThat(job.get("primaryUrl").asText()).startsWith("https://www.adzuna.co.za/land/ad/a1");
    }

    @Test
    void searchNeedsWhatAndNoBoardToken() {
        Res r = api.post("/api/feed/sources", "{\"kind\":\"ADZUNA\",\"boardToken\":\"acme\",\"verify\":false}");
        JsonNode err = assertError(r, 400, "VALIDATION_FAILED");
        assertThat(fields(err)).containsExactlyInAnyOrder("boardToken", "options.what");
    }

    @Test
    void verifyRunsTheSearchOnceAndCountsIt() {
        adzunaReplies();
        Res r = api.post("/api/feed/sources", "{\"kind\":\"ADZUNA\",\"options\":{\"what\":\"cobol\"}}");
        assertThat(r.status()).isEqualTo(201);
        assertThat(r.json().get("warnings").get(0).asText()).contains("finds no jobs right now");
        assertThat(STUB.requests(PAGE1)).singleElement()
                .satisfies(c -> assertThat(c.uri().getRawQuery()).contains("results_per_page=1"));
        assertThat(requestsToday()).isEqualTo(1);
    }

    // ------------------------------------------------------------------ 9. budget

    @Test
    void budgetSpentMeansNoRequestUntilMidnightAndNoFailureCounted() {
        UUID s = adzunaSearch("java");
        adzunaReplies(new Ad("a1", "Java Developer", "Acme", start.minus(Duration.ofHours(1))));
        pollOk(s);
        pollSync(s);                                            // identical body: NOT_MODIFIED, still a request
        pollSync(s);
        assertThat(requestsToday()).isEqualTo(3);
        int before = STUB.requests(PAGE1).size();

        PollOutcome o = pollSync(s);
        assertThat(o).isInstanceOf(PollOutcome.Failed.class);
        assertThat(((PollOutcome.Failed) o).status()).isEqualTo("BUDGET_EXHAUSTED");
        assertThat(STUB.requests(PAGE1)).hasSize(before);
        Map<String, Object> row = sourceRow(s);
        assertThat(row.get("last_status")).isEqualTo("BUDGET_EXHAUSTED");
        assertThat(row.get("consecutive_failures")).isEqualTo(0);
        Instant midnight = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC).plusDays(1)
                .atStartOfDay(ZoneOffset.UTC).toInstant();
        assertThat(instant(row.get("next_poll_at"))).isBetween(midnight, midnight.plus(Duration.ofMinutes(5)));
        assertThat(requestsToday()).isEqualTo(3);

        JsonNode status = api.get("/api/feed/status").json().get("aggregator");
        assertThat(status.get("configured").asBoolean()).isTrue();
        assertThat(status.get("requestsToday").asInt()).isEqualTo(3);
        assertThat(status.get("dailyBudget").asInt()).isEqualTo(3);
    }

    @Test
    void effectiveIntervalSharesTheBudget() {
        UUID a = adzunaSearch("java");
        adzunaSearch("python");
        // 24h × 2 / 3 = 57600 s
        assertThat(api.get("/api/feed/sources/" + a).json().get("effectivePollIntervalSeconds").asInt())
                .isEqualTo(57_600);
    }

    // ------------------------------------------------------------------ page 2

    @Test
    void fullFirstPageOfNewResultsFetchesPage2() {
        UUID s = adzunaSearch("java");
        adzunaReplies(new Ad("a1", "Java Developer", "Acme", start.minus(Duration.ofDays(1))));
        pollOk(s);
        jdbc.update("UPDATE feed_source SET last_success_at = ? WHERE id = ?",
                Timestamp.from(start.minus(Duration.ofHours(2))), s);

        adzunaReplies(new Ad("n1", "Java Developer", "Beta", start.minus(Duration.ofMinutes(10))),
                new Ad("n2", "Java Engineer", "Gamma", start.minus(Duration.ofMinutes(20))));
        STUB.route(PAGE2, Reply.json(200, body(new Ad("n3", "Java Lead", "Delta",
                start.minus(Duration.ofMinutes(30))))));
        PollOutcome.Ok ok = pollOk(s);
        assertThat(ok.stats().created()).isEqualTo(3);
        assertThat(STUB.requests(PAGE2)).hasSize(1);
        assertThat(requestsToday()).isEqualTo(3);
    }

    // ------------------------------------------------------------------ closing

    @Test
    void absenceNeverClosesButLongUnseenPostingsDo() {
        UUID s = adzunaSearch("java");
        adzunaReplies(new Ad("a1", "Java Developer", "Acme", start.minus(Duration.ofHours(1))),
                new Ad("a2", "Java Engineer", "Beta", start.minus(Duration.ofHours(1))));
        pollOk(s);
        adzunaReplies(new Ad("a2", "Java Engineer", "Beta", start.minus(Duration.ofHours(1))));
        PollOutcome.Ok ok = pollOk(s);
        assertThat(ok.stats().closed()).isZero();
        assertThat(posting(s, "a1").get("closed_at")).isNull();

        jdbc.update("UPDATE job_posting SET last_seen_at = ? WHERE source_id = ? AND external_id = 'a1'",
                Timestamp.from(start.minus(Duration.ofDays(15))), s);
        adzunaReplies(new Ad("a3", "Java Lead", "Gamma", start.minus(Duration.ofHours(1))));
        ok = pollOk(s);
        assertThat(ok.stats().closed()).isEqualTo(1);
        assertThat(posting(s, "a1").get("closed_at")).isNotNull();
        assertThat(feedJob(jobOf(s, "a1")).get("closed_at")).isNotNull();
        assertThat(posting(s, "a2").get("closed_at")).isNull();
    }

    // ------------------------------------------------------------------ 5. dedup with a company board

    @Test
    void sameRoleFromLeverAndAdzunaIsOneJobWithTheLeverDescription() {
        UUID lever = lever("acme", "Acme");
        leverReplies("acme", new LeverJob("l1", "Senior Java Developer",
                "Full Lever description: Java, Spring, PostgreSQL.", start.minus(Duration.ofHours(2)), "onsite",
                "https://jobs.lever.co/acme/l1"));
        pollOk(lever);
        UUID s = adzunaSearch("java");
        adzunaReplies(new Ad("z1", "Senior Java Developer", "Acme (Pty) Ltd", start.minus(Duration.ofHours(1))));
        pollOk(s);

        UUID job = jobOf(lever, "l1");
        assertThat(jobOf(s, "z1")).isEqualTo(job);
        assertThat((String) job(job).get("description")).startsWith("Full Lever description");
        JsonNode view = api.get("/api/feed/jobs/" + job).json();
        assertThat(view.get("sources")).extracting(n -> n.get("via").asText())
                .containsExactlyInAnyOrder("Lever", "Adzuna");
    }

    // ------------------------------------------------------------------ 12. derived searches

    private List<String> derivedWhats() {
        return jdbc.queryForList("SELECT options->>'what' FROM feed_source WHERE managed_by = 'PREFERENCES' "
                + "ORDER BY options->>'what'", String.class);
    }

    @Test
    void preferencesDriveTheDerivedSearches() {
        assertThat(api.put("/api/preferences", """
                {"preferences": {"targetTitles": ["Backend Engineer", "Data Engineer"],
                 "regions": {"countries": ["ZA"], "includeRemote": true}}}""").status()).isEqualTo(200);
        assertThat(derivedWhats()).containsExactly("Backend Engineer", "Data Engineer", "remote");
        UUID backend = jdbc.queryForObject("SELECT id FROM feed_source WHERE options->>'what' = 'Backend Engineer'",
                UUID.class);
        UUID data = jdbc.queryForObject("SELECT id FROM feed_source WHERE options->>'what' = 'Data Engineer'",
                UUID.class);
        adzunaReplies(new Ad("d1", "Data Engineer", "Acme", start.minus(Duration.ofHours(1))));
        pollOk(data);
        UUID orphan = jobOf(data, "d1");

        assertThat(api.put("/api/preferences", """
                {"preferences": {"targetTitles": ["Backend Engineer", "Platform Engineer"],
                 "regions": {"countries": ["ZA"], "includeRemote": false}}}""").status()).isEqualTo(200);
        assertThat(derivedWhats()).containsExactly("Backend Engineer", "Platform Engineer");
        assertThat(jdbc.queryForObject("SELECT id FROM feed_source WHERE options->>'what' = 'Backend Engineer'",
                UUID.class)).as("unchanged search kept").isEqualTo(backend);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM job WHERE id = ?", Integer.class, orphan))
                .as("the removed search's job is deleted").isZero();

        // read-only through the API
        assertError(api.delete("/api/feed/sources/" + backend), 409, "DATA_CONFLICT");

        // idempotent
        AggregatorQueries.Result again = aggregatorQueries.reconcile();
        assertThat(again.added()).isZero();
        assertThat(again.removed()).isZero();
        assertThat(again.kept()).isEqualTo(2);
    }

    @Test
    void ownerSearchWithTheSameKeyIsNotDuplicated() {
        adzunaSearch("Backend Engineer");
        api.put("/api/preferences", """
                {"preferences": {"targetTitles": ["backend   engineer"],
                 "regions": {"countries": ["ZA"], "includeRemote": false}}}""");
        assertThat(derivedWhats()).isEmpty();
        assertThat(countRows("feed_source")).isEqualTo(1);
    }
}
