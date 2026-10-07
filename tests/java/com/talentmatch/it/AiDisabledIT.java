package com.talentmatch.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.talentmatch.support.AbstractApiIT;
import com.talentmatch.support.Api.Res;
import com.talentmatch.support.TestPdfs;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

/**
 * Spec §8 / §10: with AI disabled (the Phase 2 context) regenerate is not rate-limited and no LLM beans exist.
 * Phase 4: a CV upload still works and fails with AI_DISABLED; the profile can be entered by hand.
 */
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

    @Test
    void cvUploadFailsWithAiDisabledAndManualProfileWorks() {
        assertThat(context.getBeanNamesForType(com.talentmatch.profile.ResumeExtractionModel.class)).isEmpty();
        assertThat(context.getBeanNamesForType(com.talentmatch.ai.LocalModelGate.class)).isEmpty();

        Res up = api.upload("/api/profile/resume", "file", "cv.pdf", "application/pdf", TestPdfs.cv());
        assertThat(up.status()).as(up.toString()).isEqualTo(202);
        String id = up.json().get("id").asText();
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(20)).until(() ->
                "FAILED".equals(api.get("/api/profile/resume/" + id).json().get("status").asText()));
        JsonNode failed = api.get("/api/profile/resume/" + id).json();
        assertThat(failed.get("failureReason").asText()).isEqualTo("AI_DISABLED");
        assertThat(failed.get("message").asText()).contains("PUT /api/profile");
        assertThat(failed.get("model").isNull()).isTrue();

        skills("Java");
        Res saved = api.put("/api/profile", "{\"resumeId\":\"" + id + "\",\"profile\":{\"fullName\":\"Ada Lovelace\","
                + "\"email\":\"ada@example.com\",\"skills\":[{\"name\":\"Java\",\"years\":5}]}}");
        assertThat(saved.status()).as(saved.toString()).isEqualTo(200);
        assertThat(saved.json().get("resumeId").asText()).isEqualTo(id);
        assertThat(saved.json().at("/skills/0/yearsExperience").asInt()).isEqualTo(5);
    }
}
