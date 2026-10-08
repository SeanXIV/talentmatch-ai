package com.talentmatch.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.talentmatch.feed.PollOutcome;
import com.talentmatch.feed.SourcePoller;
import com.talentmatch.feed.source.SourceProperties;
import com.talentmatch.support.AbstractFeedIT;
import com.talentmatch.support.RouteStubServer.Reply;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** §4.2.1 / §4.4 Greenhouse detail calls during a poll: budget, order, pending, re-fetch, 404. */
class FeedGreenhouseDetailsIT extends AbstractFeedIT {

    private final Instant t = Instant.now().truncatedTo(ChronoUnit.SECONDS).minus(Duration.ofDays(3));

    private GhJob gh(String id, String title) {
        return new GhJob(id, title, "Acme", "Remote", t, t, "<p>About " + title + "</p><ul><li>Java</li></ul>");
    }

    private SourcePoller pollerWithBudget(int budget) {
        SourceProperties sp = sourceProperties;
        SourceProperties custom = new SourceProperties(sp.maxPostingsPerSource(), sp.http(),
                new SourceProperties.Greenhouse(sp.greenhouse().baseUrl(), budget), sp.lever(), sp.ashby());
        return new SourcePoller(adapters, writer, sources, postingRepository, feedProperties, custom, clock);
    }

    private int detailRequests(String token, String id) {
        return STUB.requests(ghDetailPath(token, id)).size();
    }

    private Object description(UUID s, String id) {
        return posting(s, id).get("description");
    }

    @Test
    void detailsFetchedWithinTheBudget() {
        UUID s = greenhouse("acme");
        ghReplies("acme", gh("1", "Backend Engineer"), gh("2", "Data Engineer"));
        STUB.route(ghListPath("acme"), Reply.json(200, ghListBody(gh("1", "Backend Engineer"),
                gh("2", "Data Engineer"))).withHeader("ETag", "\"g1\""));
        PollOutcome.Ok ok = pollOk(s);
        assertThat(ok.stats().detailCalls()).isEqualTo(2);
        assertThat((String) description(s, "1")).contains("About Backend Engineer").contains("Java")
                .doesNotContain("<");
        assertThat((BigDecimal) posting(s, "1").get("salary_min")).isEqualByComparingTo("100000");
        assertThat((BigDecimal) posting(s, "1").get("salary_max")).isEqualByComparingTo("150000");
        assertThat(((String) posting(s, "1").get("salary_currency")).strip()).isEqualTo("USD");
        assertThat(detailRequests("acme", "1")).isEqualTo(1);
        assertThat(STUB.requests(ghDetailPath("acme", "1")).get(0).uri().getQuery()).contains("pay_transparency=true");
        // nothing pending: ETag and body hash are stored
        assertThat(sourceRow(s).get("etag")).isEqualTo("\"g1\"");
        assertThat(sourceRow(s).get("content_hash")).isNotNull();
        assertThat(job(jobOf(s, "1")).get("description")).isNotNull();
    }

    @Test
    void budgetOfOneLeavesTheRestPendingWithoutEtagAndFillsThemLater() {
        UUID s = greenhouse("acme");
        GhJob a = gh("1", "Backend Engineer");
        GhJob b = gh("2", "Data Engineer");
        GhJob c = gh("3", "QA Engineer");
        ghReplies("acme", a, b, c);
        STUB.route(ghListPath("acme"), Reply.json(200, ghListBody(a, b, c)).withHeader("ETag", "\"g1\""));
        SourcePoller one = pollerWithBudget(1);

        PollOutcome first = pollSync(one, s);
        assertThat(first).isInstanceOf(PollOutcome.Ok.class);
        assertThat(((PollOutcome.Ok) first).stats().detailCalls()).isEqualTo(1);
        assertThat(((PollOutcome.Ok) first).stats().created()).isEqualTo(3);
        long pending = jdbc.queryForObject("SELECT count(*) FROM job_posting WHERE source_id = ? AND description IS NULL",
                Long.class, s);
        assertThat(pending).isEqualTo(2);
        assertThat(sourceRow(s).get("etag")).as("no ETag while details are pending").isNull();
        assertThat(sourceRow(s).get("content_hash")).isNull();
        // pending postings already have a job (no description yet)
        assertThat(countRows("feed_job")).isEqualTo(3);

        PollOutcome second = pollSync(one, s);
        assertThat(second).as("same body, but nothing stored to compare: a full fetch")
                .isInstanceOf(PollOutcome.Ok.class);
        assertThat(STUB.requests(ghListPath("acme")).get(1).header("If-None-Match")).isNull();
        assertThat(((PollOutcome.Ok) second).stats().detailCalls()).isEqualTo(1);
        assertThat(((PollOutcome.Ok) second).stats().updated()).isEqualTo(1);
        assertThat(sourceRow(s).get("etag")).isNull();

        pollSync(one, s);
        pending = jdbc.queryForObject("SELECT count(*) FROM job_posting WHERE source_id = ? AND description IS NULL",
                Long.class, s);
        assertThat(pending).isZero();
        assertThat(sourceRow(s).get("etag")).isEqualTo("\"g1\"");
        assertThat(sourceRow(s).get("content_hash")).isNotNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM job WHERE description IS NULL", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM feed_job WHERE description_hash IS NULL", Long.class))
                .isZero();
        // each detail fetched exactly once
        assertThat(detailRequests("acme", "1") + detailRequests("acme", "2") + detailRequests("acme", "3"))
                .isEqualTo(3);
    }

    @Test
    void newPostingsAreFetchedBeforePendingOnes() {
        UUID s = greenhouse("acme");
        GhJob a = gh("1", "Backend Engineer");
        GhJob b = gh("2", "Data Engineer");
        ghReplies("acme", a, b);
        SourcePoller one = pollerWithBudget(1);
        pollSync(one, s);                               // one of them stays pending
        String pendingId = description(s, "1") == null ? "1" : "2";

        GhJob n = gh("9", "Brand New Engineer");
        ghReplies("acme", a, b, n);
        pollSync(one, s);
        assertThat(description(s, "9")).as("new before pending").isNotNull();
        assertThat(description(s, pendingId)).isNull();
    }

    @Test
    void changedUpdatedAtTriggersARefetch() {
        UUID s = greenhouse("acme");
        GhJob a = gh("1", "Backend Engineer");
        GhJob b = gh("2", "Data Engineer");
        ghReplies("acme", a, b);
        pollOk(s);
        assertThat(detailRequests("acme", "1")).isEqualTo(1);

        GhJob changed = a.withUpdatedAt(t.plus(Duration.ofHours(1))).withContent("<p>Rewritten role text</p>");
        ghReplies("acme", changed, b);
        PollOutcome.Ok ok = pollOk(s);
        assertThat(ok.stats().detailCalls()).isEqualTo(1);
        assertThat(detailRequests("acme", "1")).isEqualTo(2);
        assertThat(detailRequests("acme", "2")).as("unchanged: no detail call").isEqualTo(1);
        assertThat((String) description(s, "1")).contains("Rewritten role text");
        assertThat((String) job(jobOf(s, "1")).get("description")).contains("Rewritten role text");
        assertThat(ok.stats().updated()).isEqualTo(1);
    }

    @Test
    void detail404InsertsThePostingWithoutDescription() {
        UUID s = greenhouse("acme");
        GhJob a = gh("1", "Backend Engineer");
        GhJob gone = gh("2", "Data Engineer");
        ghReplies("acme", a, gone);
        STUB.route(ghDetailPath("acme", "2"), Reply.json(404, "{\"status\":404}"));
        PollOutcome.Ok ok = pollOk(s);
        assertThat(ok.stats().created()).isEqualTo(2);
        assertThat(description(s, "2")).isNull();
        assertThat(posting(s, "2").get("title")).isEqualTo("Data Engineer");
        UUID jobId = jobOf(s, "2");
        assertThat(job(jobId).get("description")).isNull();
        assertThat(feedJob(jobId).get("description_hash")).isNull();
        assertThat(description(s, "1")).isNotNull();
        assertThat(sourceRow(s).get("content_hash")).as("still pending: no body hash").isNull();
    }

    @Test
    void zeroBudgetTurnsDetailsOff() {
        UUID s = greenhouse("acme");
        ghReplies("acme", gh("1", "Backend Engineer"));
        PollOutcome o = pollSync(pollerWithBudget(0), s);
        assertThat(o).isInstanceOf(PollOutcome.Ok.class);
        assertThat(((PollOutcome.Ok) o).stats().detailCalls()).isZero();
        assertThat(detailRequests("acme", "1")).isZero();
        assertThat(description(s, "1")).isNull();
        assertThat(sourceRow(s).get("content_hash")).as("details off: nothing pending").isNotNull();
    }
}
