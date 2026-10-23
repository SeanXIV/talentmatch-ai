package com.talentmatch.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.talentmatch.application.tailoring.GenerationFailure;
import com.talentmatch.application.tailoring.TailoringFixtures;
import com.talentmatch.application.tailoring.TailoringModel;
import com.talentmatch.support.AbstractApiIT;
import com.talentmatch.support.Api.Res;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

/**
 * Phase 7 §9.2 item 5 / §5.4: with AI disabled (the Phase 2 context) a generation request is still 202 and
 * the row becomes FAILED/AI_DISABLED; no TailoringModel bean exists, but the generation executor does.
 */
class GenerationAiDisabledIT extends AbstractApiIT {

    @Autowired
    private ApplicationContext context;

    @AfterEach
    void drain() {
        awaitApplicationIdle();
    }

    private void saveProfile() {
        ObjectNode body = mapper.createObjectNode();
        body.put("createMissingSkills", true);
        body.set("profile", mapper.valueToTree(TailoringFixtures.profile()));
        Res r = api.put("/api/profile", body.toString());
        assertThat(r.status()).as(r.toString()).isEqualTo(200);
    }

    private JsonNode awaitFailed(UUID appId, UUID genId) {
        String path = "/api/applications/" + appId + "/generations/" + genId;
        await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(50))
                .until(() -> "FAILED".equals(api.get(path).json().get("status").asText()));
        return api.get(path).json();
    }

    @Test
    void generationsFailWithAiDisabled() {
        assertThat(context.getBeanNamesForType(TailoringModel.class)).isEmpty();
        assertThat(context.containsBean("applicationExecutor")).isTrue();
        skills("Java");
        saveProfile();
        UUID job = job("Senior Java Developer", "Initech", "Java");

        Res created = api.post("/api/applications", "{\"jobId\": \"" + job + "\", \"generate\": true}");
        assertThat(created.status()).as(created.toString()).isEqualTo(201);
        UUID appId = UUID.fromString(created.json().get("id").asText());
        UUID genId = UUID.fromString(created.json().at("/latestGeneration/id").asText());
        JsonNode failed = awaitFailed(appId, genId);
        assertThat(failed.get("failureReason").asText()).isEqualTo("AI_DISABLED");
        assertThat(failed.get("hint").asText()).isEqualTo(GenerationFailure.AI_DISABLED.hint());
        assertThat(failed.get("model").isNull()).isTrue();
        assertThat(failed.get("attempts").asInt()).isEqualTo(1);
        assertThat(failed.get("results").size()).isZero();

        Res post = api.post("/api/applications/" + appId + "/generations", null);
        assertThat(post.status()).as(post.toString()).isEqualTo(202);
        assertThat(awaitFailed(appId, UUID.fromString(post.json().get("id").asText())).get("failureReason").asText())
                .isEqualTo("AI_DISABLED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM application_document", Integer.class)).isZero();
    }
}
