package com.talentmatch.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.talentmatch.feed.FeedScheduler;
import com.talentmatch.support.AbstractApiIT;
import com.talentmatch.support.Api.Res;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.test.context.TestPropertySource;

/** {@code talentmatch.feed.enabled=false}: no polling, endpoints still answer (§5.1, §5.2, §6.5). */
@TestPropertySource(properties = {"talentmatch.feed.enabled=false", "talentmatch.feed.scheduler.enabled=true"})
class FeedDisabledIT extends AbstractApiIT {

    @Autowired
    private FeedScheduler scheduler;

    @Autowired
    private ApplicationContext context;

    @Autowired
    private com.talentmatch.feed.FeedProcessor processor;

    private UUID source() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("kind", "LEVER");
        body.put("boardToken", "acme");
        body.put("verify", false);
        Res r = api.post("/api/feed/sources", api.json(body));
        assertThat(r.status()).as("%s", r).isEqualTo(201);
        return UUID.fromString(r.json().get("id").asText());
    }

    @Test
    void pollIs409FeedDisabled() {
        UUID id = source();
        assertError(api.post("/api/feed/sources/" + id + "/poll", null), 409, "FEED_DISABLED");
        assertThat(jdbc.queryForObject("SELECT lease_until IS NULL FROM feed_source WHERE id = ?", Boolean.class, id))
                .isTrue();
    }

    @Test
    void tickDoesNothing() {
        UUID id = source();
        jdbc.update("UPDATE feed_source SET next_poll_at = now() - interval '1 hour' WHERE id = ?", id);
        assertThat(scheduler.tick()).isZero();
        assertThat(jdbc.queryForObject("SELECT last_polled_at IS NULL FROM feed_source WHERE id = ?", Boolean.class,
                id)).isTrue();
    }

    @Test
    void healthIsUpWithEnabledFalse() {
        source();
        Res r = api.get("/actuator/health");
        assertThat(r.status()).isEqualTo(200);
        JsonNode feed = r.json().get("components").get("feed");
        assertThat(feed.get("status").asText()).isEqualTo("UP");
        assertThat(feed.get("details").get("enabled").asBoolean()).isFalse();
    }

    @Test
    void statusReportsDisabled() {
        JsonNode s = api.get("/api/feed/status").json();
        assertThat(s.get("enabled").asBoolean()).isFalse();
        assertThat(s.get("schedulerEnabled").asBoolean()).isFalse();
    }

    @Test
    void noSchedulingWhenTheFeedIsOff() {
        assertThat(context.getBeanNamesForType(SchedulingConfigurer.class))
                .noneMatch(n -> n.toLowerCase().contains("feedscheduling"));
        // nothing else in the app enables scheduling either
        assertThat(context.getBeanNamesForType(ScheduledAnnotationBeanPostProcessor.class)).isEmpty();
    }

    @Test
    void processDueReturnsZero() {
        skills("Java");
        UUID job = jdbc.queryForObject("INSERT INTO job (title, company, description, origin) "
                + "VALUES ('Backend Engineer', 'Acme', 'Java', 'FEED') RETURNING id", UUID.class);
        jdbc.update("INSERT INTO feed_job (job_id, dedup_key, primary_url, process_after) "
                + "VALUES (?, 'k', 'https://x.test/j', now() - interval '1 minute')", job);
        assertThat(processor.processDue()).isZero();
        assertThat(processor.process(job)).isFalse();
        assertThat(jdbc.queryForObject("SELECT process_after IS NOT NULL FROM feed_job WHERE job_id = ?",
                Boolean.class, job)).as("left untouched").isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM job_skill", Integer.class)).isZero();
    }
}
