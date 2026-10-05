package com.talentmatch.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.talentmatch.support.AbstractApiIT;
import com.talentmatch.support.Api.Res;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

/** Spec §8 / §10: with AI disabled (the Phase 2 context) regenerate is not rate-limited and no LLM beans exist. */
class AiDisabledIT extends AbstractApiIT {

    @Autowired
    ApplicationContext context;

    @Test
    void twoRegeneratesAreBoth200AndEveryItemHasAnAiDisabledTemplate() {
        skills("Java", "Docker");
        UUID job = job("Backend Engineer", "Acme", "Java", "~Docker");
        candidate("Ada Lovelace", "ada@example.com", "Java:5");
        candidate("Bob Builder", "bob@example.com", "Docker");
        for (int i = 0; i < 2; i++) {
            Res r = api.get("/api/jobs/" + job + "/matches?regenerate=true");
            assertThat(r.status()).as(r.toString()).isEqualTo(200);
            assertThat(r.json().get("explanationsGenerated").asInt()).isZero();
            for (JsonNode m : r.json().get("matches")) {
                assertThat(m.get("aiExplanation").isNull()).isTrue();
                assertThat(m.get("explanationStatus").asText()).isEqualTo("UNAVAILABLE");
                assertThat(m.at("/explanation/source").asText()).isEqualTo("TEMPLATE");
                assertThat(m.at("/explanation/reason").asText()).isEqualTo("AI_DISABLED");
            }
        }
        assertThat(context.getBeanNamesForType(dev.langchain4j.model.chat.ChatModel.class)).isEmpty();
        assertThat(context.containsBean("aiExecutor")).isFalse();
    }
}
