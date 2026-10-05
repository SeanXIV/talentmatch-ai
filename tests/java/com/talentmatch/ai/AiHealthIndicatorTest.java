package com.talentmatch.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.talentmatch.support.MutableClock;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;

/** Spec §2 AiHealthIndicator / §10 unit 10. */
class AiHealthIndicatorTest {

    private final MutableClock clock = MutableClock.fixedAt(AiFixtures.T0);

    @Test
    void disabledIsUpTemplateOnly() {
        AiProperties p = AiFixtures.props(false, 5, Duration.ofSeconds(8), Duration.ofSeconds(60));
        Health h = new AiHealthIndicator(p, new AiCircuitBreaker(p, clock)).health();
        assertThat(h.getStatus()).isEqualTo(Status.UP);
        assertThat(h.getDetails()).containsExactly(Map.entry("enabled", false), Map.entry("mode", "template-only"));
    }

    @Test
    void freshCircuitIsUpWithNullDetailsThatSerialize() throws Exception {
        AiProperties p = AiFixtures.props();
        Health h = new AiHealthIndicator(p, new AiCircuitBreaker(p, clock)).health();
        assertThat(h.getStatus()).isEqualTo(Status.UP);
        assertThat(h.getDetails()).containsKeys("enabled", "provider", "model", "circuit", "consecutiveFailures",
                "lastSuccessAt", "lastFailureAt", "lastFailure");
        assertThat(h.getDetails().get("lastSuccessAt")).isNull();
        assertThat(h.getDetails().get("lastFailure")).isNull();
        assertThat(h.getDetails()).containsEntry("provider", "ollama").containsEntry("model", "qwen2.5:7b-instruct")
                .containsEntry("circuit", "CLOSED").containsEntry("consecutiveFailures", 0);
        assertThat(new ObjectMapper().writeValueAsString(h.getDetails())).contains("\"lastFailure\":null");
    }

    @Test
    void openCircuitIsDegradedWithDetailsAndNoSecrets() {
        AiProperties p = new AiProperties(true, AiProvider.CLAUDE, 5, Duration.ofSeconds(60), Duration.ofSeconds(8),
                2, 20, 2000, Duration.ofSeconds(60), Duration.ofSeconds(60), new AiProperties.Circuit(3,
                Duration.ofSeconds(30)), null, null,
                new AiProperties.Claude("sk-ant-super-secret", "https://user:pw@proxy.example", null, 3000, "", false));
        AiCircuitBreaker circuit = new AiCircuitBreaker(p, clock);
        circuit.recordSuccess();
        for (int i = 0; i < 3; i++) {
            circuit.recordFailure(FailureKind.TIMEOUT);
        }
        Health h = new AiHealthIndicator(p, circuit).health();
        assertThat(h.getStatus().getCode()).isEqualTo("DEGRADED");
        assertThat(h.getDetails()).containsEntry("enabled", true).containsEntry("provider", "claude")
                .containsEntry("model", "claude-sonnet-5-5").containsEntry("circuit", "OPEN")
                .containsEntry("consecutiveFailures", 3).containsEntry("lastFailure", "TIMEOUT")
                .containsEntry("lastSuccessAt", AiFixtures.T0.toString())
                .containsEntry("lastFailureAt", AiFixtures.T0.toString());
        assertThat(h.getDetails().toString()).doesNotContain("sk-ant").doesNotContain("pw@").doesNotContain("proxy");

        clock.advance(Duration.ofSeconds(30));
        Health half = new AiHealthIndicator(p, circuit).health();
        assertThat(half.getStatus()).as("HALF_OPEN is reported as UP").isEqualTo(Status.UP);
        assertThat(half.getDetails()).containsEntry("circuit", "HALF_OPEN");
    }
}
