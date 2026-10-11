package com.talentmatch.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.talentmatch.TalentMatchApplication;
import com.talentmatch.support.AbstractApiIT;
import com.talentmatch.support.Api.Res;
import com.talentmatch.support.TestcontainersConfiguration;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.ollama.OllamaChatModel;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;

/**
 * Spec §10 integration 1: the real Ollama ChatModel pointed at a closed port (no fake). The app starts,
 * serves template explanations (PROVIDER_UNAVAILABLE), and health stays UP with ai DEGRADED.
 * Also: the claude profile without ANTHROPIC_API_KEY fails startup with the analyzer message.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "talentmatch.ai.enabled=true",
        "talentmatch.ai.provider=ollama",
        "talentmatch.ai.ollama.base-url=http://localhost:1",
        "talentmatch.ai.request-budget=2s",
        "talentmatch.ai.call-timeout=3s",
        "talentmatch.ai.top-n=3",
        // Phase 5: no feed scheduling, no real provider network (same as AbstractApiIT)
        "talentmatch.feed.scheduler.enabled=false",
        "talentmatch.feed.greenhouse.base-url=http://localhost:1",
        "talentmatch.feed.lever.base-url=http://localhost:1",
        "talentmatch.feed.lever.eu-base-url=http://localhost:1",
        "talentmatch.feed.ashby.base-url=http://localhost:1",
        "talentmatch.feed.adzuna.base-url=http://localhost:1"})
@Import(TestcontainersConfiguration.class)
@ExtendWith(OutputCaptureExtension.class)
class AiProviderUnavailableIT extends AbstractApiIT {

    @Autowired
    ChatModel chatModel;

    @Test
    void startsWithV5AndServesTemplatesWhileOllamaIsUnreachable() {
        assertThat(chatModel).isInstanceOf(OllamaChatModel.class);
        assertThat(jdbc.queryForList("SELECT version FROM flyway_schema_history WHERE success AND version IS NOT NULL "
                + "ORDER BY installed_rank", String.class)).containsExactly("1", "2", "3", "4", "5");

        skills("Java", "SQL", "Docker");
        UUID job = job("Backend Engineer", "Acme", "Java", "SQL", "~Docker");
        candidate("Ada Lovelace", "ada@example.com", "Java:5", "SQL", "Docker");
        candidate("Bob Builder", "bob@example.com", "Java", "SQL");
        candidate("Cy Young", "cy@example.com", "Java");
        candidate("Di Prince", "di@example.com", "Docker");

        for (int i = 0; i < 3; i++) {
            Res r = api.get("/api/jobs/" + job + "/matches");
            assertThat(r.status()).as(r.toString()).isEqualTo(200);
            JsonNode page = r.json();
            assertThat(page.get("explanationsGenerated").asInt()).isZero();
            for (int k = 0; k < 4; k++) {
                JsonNode m = page.get("matches").get(k);
                assertThat(m.get("explanationStatus").asText()).isEqualTo("UNAVAILABLE");
                assertThat(m.get("aiExplanation").isNull()).isTrue();
                assertThat(m.at("/explanation/source").asText()).isEqualTo("TEMPLATE");
                assertThat(m.at("/explanation/reason").asText())
                        .isEqualTo(k < 3 ? "PROVIDER_UNAVAILABLE" : "NOT_IN_TOP_N");
            }
            assertNoLeaks(r);
        }

        Res health = api.get("/actuator/health");
        assertThat(health.status()).as(health.toString()).isEqualTo(200);
        assertThat(health.json().get("status").asText()).isEqualTo("UP");
        assertThat(health.json().at("/components/ai/status").asText()).isEqualTo("DEGRADED");
        assertThat(health.json().at("/components/ai/details/circuit").asText()).isEqualTo("OPEN");
        assertThat(health.json().at("/components/db/status").asText()).isEqualTo("UP");
        for (String probe : List.of("/actuator/health/readiness", "/actuator/health/liveness")) {
            Res p = api.get(probe);
            assertThat(p.status()).as(probe).isEqualTo(200);
            assertThat(p.json().get("status").asText()).isEqualTo("UP");
        }
    }

    @Test
    void claudeProfileWithoutKeyFailsStartupWithAnActionableMessage(CapturedOutput output) {
        assertThatThrownBy(() -> new SpringApplicationBuilder(TalentMatchApplication.class).profiles("claude").run(
                "--server.port=0",
                "--talentmatch.ai.enabled=true",
                "--talentmatch.ai.claude.api-key=",
                "--spring.datasource.url=" + postgres.getJdbcUrl(),
                "--spring.datasource.username=" + postgres.getUsername(),
                "--spring.datasource.password=" + postgres.getPassword()))
                .isInstanceOf(Exception.class);
        assertThat(output.getAll())
                .contains("APPLICATION FAILED TO START")
                .contains("Profile 'claude' is active but ANTHROPIC_API_KEY is not set.")
                .contains("Export it, or drop the profile to use local Ollama (default) or set AI_ENABLED=false.");
    }
}
