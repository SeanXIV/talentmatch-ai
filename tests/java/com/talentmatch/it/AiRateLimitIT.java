package com.talentmatch.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.talentmatch.support.AbstractAiApiIT;
import com.talentmatch.support.Api.Res;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Spec §8 / §10 integration 13: regenerate is rate-limited per job when AI is enabled. */
class AiRateLimitIT extends AbstractAiApiIT {

    @Test
    void secondRegenerateIs429WithRetryAfterAndFullErrorShape() {
        UUID job = backendJob();
        fiveCandidates();
        assertThat(getMatches(job, "regenerate=true").status()).isEqualTo(200);

        Res r = getMatches(job, "regenerate=true");
        JsonNode body = assertError(r, 429, "REGENERATE_RATE_LIMITED");
        int retry = Integer.parseInt(r.header("Retry-After"));
        assertThat(retry).isBetween(1, 60);
        assertThat(body.get("message").asText()).isEqualTo("Matches for this job were regenerated recently. You can "
                + "regenerate again in " + retry + (retry == 1 ? " second" : " seconds")
                + "; reload without regenerate=true to see the current results.");
        assertThat(body.get("error").asText()).isEqualTo("Too Many Requests");
        assertThat(body.get("path").asText()).isEqualTo("/api/jobs/" + job + "/matches");

        // a plain GET is never limited
        assertThat(getMatches(job, null).status()).isEqualTo(200);
        // another job has its own slot
        UUID other = backendJob("Platform Engineer");
        assertThat(getMatches(other, "regenerate=true").status()).isEqualTo(200);
        // after the window the job can be regenerated again
        clock.advance(Duration.ofSeconds(61));
        assertThat(getMatches(job, "regenerate=true").status()).isEqualTo(200);
    }

    @Test
    void failedRequestsDoNotConsumeTheSlot() {
        UUID missing = UUID.randomUUID();
        assertError(getMatches(missing, "regenerate=true"), 404, "JOB_NOT_FOUND");
        assertError(getMatches(missing, "regenerate=true"), 404, "JOB_NOT_FOUND");

        UUID job = backendJob();
        fiveCandidates();
        // parameter validation (400) runs before the limiter
        Res bad = getMatches(job, "regenerate=true&limit=0");
        assertThat(bad.status()).isEqualTo(400);
        assertThat(getMatches(job, "regenerate=true").status()).isEqualTo(200);
    }
}
