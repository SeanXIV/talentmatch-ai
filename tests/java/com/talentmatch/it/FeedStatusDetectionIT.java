package com.talentmatch.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.talentmatch.support.AbstractFeedIT;
import com.talentmatch.support.Api.Res;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Step 11: {@code GET /api/feed/status} {@code detection}: the median of
 * {@code sent_at − coalesce(posted_at, first_seen_at)} over SENT notifications of the last 7 days.
 * Feed jobs come from one stubbed Lever poll; notifications and times are seeded with JDBC.
 */
class FeedStatusDetectionIT extends AbstractFeedIT {

    private final Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);

    /** Polls a Lever board with {@code n} postings and returns their feed job ids. */
    private List<UUID> jobs(int n) {
        UUID s = lever("acme", "Acme");
        LeverJob[] postings = new LeverJob[n];
        for (int i = 0; i < n; i++) {
            postings[i] = new LeverJob("p" + i, "Engineer " + i, "We use Java " + i + ".",
                    now.minus(Duration.ofDays(30)));
        }
        leverReplies("acme", postings);
        pollOk(s);
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            ids.add(jobOf(s, "p" + i));
        }
        return ids;
    }

    private void times(UUID job, Instant postedAt, Instant firstSeenAt) {
        jdbc.update("UPDATE feed_job SET posted_at = ?, first_seen_at = ? WHERE job_id = ?",
                postedAt == null ? null : Timestamp.from(postedAt), Timestamp.from(firstSeenAt), job);
    }

    private void notification(UUID job, String status, Instant sentAt) {
        jdbc.update("INSERT INTO feed_notification (job_id, channel, status, score, attempts, sent_at) "
                        + "VALUES (?, 'EMAIL', ?, 0.8, 1, ?)", job, status,
                sentAt == null ? null : Timestamp.from(sentAt));
    }

    /** A job posted at {@code base} whose email was sent {@code seconds} later. */
    private void sent(UUID job, Instant base, long seconds) {
        times(job, base, base.minusSeconds(3600));
        notification(job, "SENT", base.plusSeconds(seconds));
    }

    private JsonNode detection() {
        Res r = api.get("/api/feed/status");
        assertThat(r.status()).as("%s", r).isEqualTo(200);
        return r.json().get("detection");
    }

    @Test
    void medianOverSentEmailsOfTheLastSevenDays() {
        List<UUID> j = jobs(7);
        Instant base = now.minus(Duration.ofHours(2));
        sent(j.get(0), base, 60);
        sent(j.get(1), base, 120);
        sent(j.get(2), base, 600);
        // outside the window: sent 8 days ago, a huge latency that would move the median
        Instant old = now.minus(Duration.ofDays(8));
        times(j.get(3), old.minus(Duration.ofDays(1)), old.minus(Duration.ofDays(1)));
        notification(j.get(3), "SENT", old);
        // not sent
        times(j.get(4), base.minus(Duration.ofDays(1)), base.minus(Duration.ofDays(1)));
        notification(j.get(4), "PENDING", null);
        times(j.get(5), base.minus(Duration.ofDays(1)), base.minus(Duration.ofDays(1)));
        notification(j.get(5), "FAILED", null);
        // j6 has no notification at all

        JsonNode d = detection();
        assertThat(d.get("p50LatencySeconds").asLong()).isEqualTo(120);
        assertThat(d.get("p50LatencySeconds").isIntegralNumber()).isTrue();
        assertThat(d.get("samples").asLong()).isEqualTo(3);
        assertThat(d.get("windowDays").asInt()).isEqualTo(7);
    }

    @Test
    void anEvenCountInterpolatesAndRoundsToWholeSeconds() {
        List<UUID> j = jobs(2);
        Instant base = now.minus(Duration.ofHours(1));
        sent(j.get(0), base, 60);
        sent(j.get(1), base, 121);
        JsonNode d = detection();
        assertThat(d.get("p50LatencySeconds").asLong()).isEqualTo(91);     // 90.5 rounds half up
        assertThat(d.get("samples").asLong()).isEqualTo(2);
    }

    @Test
    void withoutAPostingDateTheFirstSeenTimeIsTheBase() {
        List<UUID> j = jobs(1);
        Instant firstSeen = now.minus(Duration.ofHours(1));
        times(j.get(0), null, firstSeen);
        notification(j.get(0), "SENT", firstSeen.plusSeconds(300));
        JsonNode d = detection();
        assertThat(d.get("p50LatencySeconds").asLong()).isEqualTo(300);
        assertThat(d.get("samples").asLong()).isEqualTo(1);
    }

    @Test
    void aPostingDateAfterTheSendCountsAsZero() {
        List<UUID> j = jobs(1);
        Instant sentAt = now.minus(Duration.ofHours(1));
        times(j.get(0), sentAt.plus(Duration.ofHours(3)), sentAt.minus(Duration.ofHours(2)));
        notification(j.get(0), "SENT", sentAt);
        JsonNode d = detection();
        assertThat(d.get("p50LatencySeconds").asLong()).isZero();
        assertThat(d.get("samples").asLong()).isEqualTo(1);
    }

    @Test
    void nothingSentGivesANullMedianAndNoSamples() {
        JsonNode d = detection();
        assertThat(d).isNotNull();
        assertThat(d.has("p50LatencySeconds")).isTrue();
        assertThat(d.get("p50LatencySeconds").isNull()).isTrue();
        assertThat(d.get("samples").asLong()).isZero();
        assertThat(d.get("windowDays").asInt()).isEqualTo(7);

        // only unsent rows: still nothing
        List<UUID> j = jobs(1);
        notification(j.get(0), "PENDING", null);
        assertThat(detection().get("p50LatencySeconds").isNull()).isTrue();
        assertThat(detection().get("samples").asLong()).isZero();
    }
}
