package com.talentmatch.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.talentmatch.feed.FeedRecovery;
import com.talentmatch.feed.JobSkillEnrichmentService;
import com.talentmatch.support.AbstractFeedProcessingIT;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** §4.7 "AI disabled": no model bean → PENDING jobs SKIPPED (dictionary only), not an error. */
class FeedEnrichmentDisabledIT extends AbstractFeedProcessingIT {

    @Autowired
    private JobSkillEnrichmentService enrichment;
    @Autowired
    private FeedRecovery feedRecovery;

    @Test
    void withoutAModelJobsKeepTheirDictionarySkills() {
        skills("Java", "SQL");
        UUID s = lever("acme", "Acme");
        serve("acme", fresh("n", "Backend Engineer", "We use Java and SQL."));
        pollOk(s);
        processor.processDue();
        UUID job = jobOf(s, "n");
        assertThat(feedJob(job).get("enrichment_status")).isEqualTo("PENDING");

        JobSkillEnrichmentService.Report r = enrichment.enrichDue();
        assertThat(r.skipped()).isEqualTo(1);
        assertThat(feedJob(job).get("enrichment_status")).isEqualTo("SKIPPED");
        assertThat(feedJob(job).get("enrichment_failure")).isNull();
        assertThat(jobSkills(job)).containsOnlyKeys("Java", "SQL");

        feedRecovery.recover();
        assertThat(feedJob(job).get("enrichment_status")).as("still no model: stays skipped").isEqualTo("SKIPPED");

        JsonNode e = api.get("/api/feed/status").json().get("enrichment");
        assertThat(e.get("aiEnabled").asBoolean()).isFalse();
        assertThat(e.get("pending").asLong()).isZero();
    }
    @Test
    void withoutAModelTheDictionaryPathStillNotifies() {
        skills("Java", "SQL");
        profile("Java", "SQL");
        notifications(true, 0.6);
        UUID s = lever("beta", "Beta");
        serve("beta", old("base", "Platform Engineer", "We use Java."));
        pollOk(s);
        serve("beta", old("base", "Platform Engineer", "We use Java."),
                fresh("n", "Backend Engineer", "We use Java and SQL."));
        jdbc.update("UPDATE feed_source SET last_polled_at = NULL, content_hash = NULL, etag = NULL WHERE id = ?", s);
        pollOk(s);
        processor.processDue();
        UUID job = jobOf(s, "n");
        assertThat(notificationCount(job)).as("dictionary skills alone reach the threshold").isEqualTo(1);

        assertThat(enrichment.enrichDue().skipped()).isGreaterThanOrEqualTo(1);
        assertThat(feedJob(job).get("enrichment_status")).isEqualTo("SKIPPED");
        jdbc.update("UPDATE feed_job SET process_after = now() WHERE job_id = ?", job);
        processor.processDue();
        assertThat(notificationCount(job)).as("no second notification").isEqualTo(1);
    }
}
