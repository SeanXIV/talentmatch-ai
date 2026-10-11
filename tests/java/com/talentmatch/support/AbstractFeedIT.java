package com.talentmatch.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.talentmatch.feed.FeedProperties;
import com.talentmatch.feed.FeedRecovery;
import com.talentmatch.feed.FeedScheduler;
import com.talentmatch.feed.FeedSource;
import com.talentmatch.feed.FeedSourceRepository;
import com.talentmatch.feed.JobPostingRepository;
import com.talentmatch.feed.PollOutcome;
import com.talentmatch.feed.PollWriter;
import com.talentmatch.feed.SourceAdapters;
import com.talentmatch.feed.SourcePoller;
import com.talentmatch.feed.FeedConfig;
import com.talentmatch.feed.source.SourceProperties;
import com.talentmatch.support.Api.Res;
import com.talentmatch.support.RouteStubServer.Reply;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Base for the step-6 poller ITs: every ATS base URL points at one shared {@link RouteStubServer},
 * the scheduler is off (inherited), and polls are driven synchronously ({@link #pollSync}) or via
 * {@link FeedScheduler#tick()}. Uses the real Clock bean (DB defaults such as
 * {@code next_poll_at DEFAULT now()} then agree with the app clock); time-dependent state is set up by
 * moving database timestamps instead of the clock.
 */
public abstract class AbstractFeedIT extends AbstractApiIT {

    protected static final RouteStubServer STUB = new RouteStubServer();

    @DynamicPropertySource
    static void feedStubProperties(DynamicPropertyRegistry registry) {
        registry.add("talentmatch.feed.greenhouse.base-url", STUB::baseUrl);
        registry.add("talentmatch.feed.lever.base-url", STUB::baseUrl);
        registry.add("talentmatch.feed.lever.eu-base-url", STUB::baseUrl);
        registry.add("talentmatch.feed.ashby.base-url", STUB::baseUrl);
        registry.add("talentmatch.feed.http.min-host-spacing", () -> "0s");
        registry.add("talentmatch.feed.http.connect-timeout", () -> "2s");
        registry.add("talentmatch.feed.http.read-timeout", () -> "5s");
    }

    @Autowired
    protected FeedScheduler scheduler;
    @Autowired
    protected SourcePoller poller;
    @Autowired
    protected FeedSourceRepository sources;
    @Autowired
    protected JobPostingRepository postingRepository;
    @Autowired
    protected PollWriter writer;
    @Autowired
    protected SourceAdapters adapters;
    @Autowired
    protected FeedProperties feedProperties;
    @Autowired
    protected SourceProperties sourceProperties;
    @Autowired
    protected FeedRecovery recovery;
    @Autowired
    protected Clock clock;
    @Autowired
    @Qualifier(FeedConfig.POLL_EXECUTOR)
    protected ThreadPoolTaskExecutor pollExecutor;

    @BeforeEach
    void resetFeedStub() {
        awaitPollThreadsIdle();
        STUB.reset();
    }

    @AfterEach
    void drainPolls() {
        awaitPollThreadsIdle();
    }

    protected void awaitPollThreadsIdle() {
        await().atMost(Duration.ofSeconds(30)).until(() -> pollExecutor.getActiveCount() == 0);
    }

    // ------------------------------------------------------------------ sources

    protected UUID source(String kind, String token, String companyName, Integer intervalSeconds) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("kind", kind);
        body.put("boardToken", token);
        body.put("companyName", companyName);
        body.put("pollIntervalSeconds", intervalSeconds);
        body.put("verify", false);
        Res r = api.post("/api/feed/sources", api.json(body));
        assertThat(r.status()).as("POST source %s", r).isEqualTo(201);
        return UUID.fromString(r.json().get("id").asText());
    }

    protected UUID lever(String token, String companyName) {
        return source("LEVER", token, companyName, null);
    }

    protected UUID greenhouse(String token) {
        return source("GREENHOUSE", token, null, null);
    }

    /** Claims the source (whatever its schedule) and polls it on the calling thread. */
    protected PollOutcome pollSync(UUID id) {
        return pollSync(poller, id);
    }

    protected PollOutcome pollSync(SourcePoller with, UUID id) {
        Instant now = clock.instant();
        FeedSource claimed = sources.claim(id, now, scheduler.leaseUntil(now))
                .orElseThrow(() -> new AssertionError("source " + id + " could not be claimed"));
        return with.poll(claimed);
    }

    protected PollOutcome.Ok pollOk(UUID id) {
        PollOutcome o = pollSync(id);
        assertThat(o).as("poll outcome").isInstanceOf(PollOutcome.Ok.class);
        return (PollOutcome.Ok) o;
    }

    protected void makeDue(UUID id) {
        jdbc.update("UPDATE feed_source SET next_poll_at = ? WHERE id = ?",
                Timestamp.from(clock.instant().minusSeconds(60)), id);
    }

    protected void makeNotDue(UUID id) {
        jdbc.update("UPDATE feed_source SET next_poll_at = ? WHERE id = ?",
                Timestamp.from(clock.instant().plus(Duration.ofHours(1))), id);
    }

    protected Map<String, Object> sourceRow(UUID id) {
        return jdbc.queryForMap("SELECT * FROM feed_source WHERE id = ?", id);
    }

    protected Instant instant(Object ts) {
        return ts == null ? null : ((Timestamp) ts).toInstant();
    }

    // ------------------------------------------------------------------ postings and jobs

    protected Map<String, Object> posting(UUID sourceId, String externalId) {
        return jdbc.queryForMap("SELECT * FROM job_posting WHERE source_id = ? AND external_id = ?", sourceId,
                externalId);
    }

    protected int postingCount(UUID sourceId) {
        return jdbc.queryForObject("SELECT count(*) FROM job_posting WHERE source_id = ?", Integer.class, sourceId);
    }

    protected int countRows(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }

    protected UUID jobOf(UUID sourceId, String externalId) {
        return (UUID) posting(sourceId, externalId).get("job_id");
    }

    protected Map<String, Object> feedJob(UUID jobId) {
        return jdbc.queryForMap("SELECT * FROM feed_job WHERE job_id = ?", jobId);
    }

    protected Map<String, Object> job(UUID jobId) {
        return jdbc.queryForMap("SELECT * FROM job WHERE id = ?", jobId);
    }

    // ------------------------------------------------------------------ Lever stub bodies

    protected static String leverPath(String token) {
        return "/v0/postings/" + token;
    }

    /** One Lever posting (REMOTE unless {@code workplace} says otherwise). */
    public record LeverJob(String id, String title, String description, Instant createdAt, String workplace,
                              String url) {

        public LeverJob(String id, String title, String description, Instant createdAt) {
            this(id, title, description, createdAt, "remote", "https://jobs.lever.co/acme/" + id);
        }

        public LeverJob withDescription(String d) {
            return new LeverJob(id, title, d, createdAt, workplace, url);
        }

        public LeverJob withUrl(String u) {
            return new LeverJob(id, title, description, createdAt, workplace, u);
        }

        Map<String, Object> json() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", id);
            m.put("text", title);
            m.put("hostedUrl", url);
            Map<String, Object> categories = new LinkedHashMap<>();
            categories.put("location", "Cape Town");
            categories.put("commitment", "Full-time");
            m.put("categories", categories);
            m.put("country", "ZA");
            m.put("workplaceType", workplace);
            m.put("createdAt", createdAt == null ? null : createdAt.toEpochMilli());
            m.put("descriptionPlain", description);
            return m;
        }
    }

    protected String leverBody(LeverJob... jobs) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (LeverJob j : jobs) {
            list.add(j.json());
        }
        return api.json(list);
    }

    protected void leverReplies(String token, LeverJob... jobs) {
        STUB.route(leverPath(token), Reply.json(200, leverBody(jobs)));
    }

    // ------------------------------------------------------------------ Greenhouse stub bodies

    protected static String ghListPath(String token) {
        return "/v1/boards/" + token + "/jobs";
    }

    protected static String ghDetailPath(String token, String id) {
        return "/v1/boards/" + token + "/jobs/" + id;
    }

    public record GhJob(String id, String title, String company, String location, Instant updatedAt,
                           Instant firstPublished, String contentHtml) {

        public GhJob withUpdatedAt(Instant u) {
            return new GhJob(id, title, company, location, u, firstPublished, contentHtml);
        }

        public GhJob withContent(String c) {
            return new GhJob(id, title, company, location, updatedAt, firstPublished, c);
        }

        Map<String, Object> listJson() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", Long.parseLong(id));
            m.put("title", title);
            m.put("absolute_url", "https://job-boards.greenhouse.io/acme/jobs/" + id);
            m.put("company_name", company);
            m.put("location", Map.of("name", location));
            m.put("updated_at", updatedAt.atOffset(ZoneOffset.UTC).toString());
            m.put("first_published", firstPublished == null ? null : firstPublished.atOffset(ZoneOffset.UTC)
                    .toString());
            return m;
        }

        Map<String, Object> detailJson() {
            Map<String, Object> m = listJson();
            // Greenhouse entity-escapes the content once
            m.put("content", contentHtml.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"));
            m.put("pay_input_ranges", List.of(Map.of("min_cents", 10_000_000, "max_cents", 15_000_000,
                    "currency_type", "USD", "title", "US")));
            return m;
        }
    }

    protected String ghListBody(GhJob... jobs) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (GhJob j : jobs) {
            list.add(j.listJson());
        }
        return api.json(Map.of("jobs", list, "meta", Map.of("total", jobs.length)));
    }

    /** Routes the list and every detail. */
    protected void ghReplies(String token, GhJob... jobs) {
        STUB.route(ghListPath(token), Reply.json(200, ghListBody(jobs)));
        for (GhJob j : jobs) {
            STUB.route(ghDetailPath(token, j.id()), Reply.json(200, api.json(j.detailJson())));
        }
    }

    protected static String httpDate(Instant at) {
        return DateTimeFormatter.RFC_1123_DATE_TIME.format(at.atOffset(ZoneOffset.UTC));
    }
}
