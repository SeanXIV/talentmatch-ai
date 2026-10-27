package com.talentmatch.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import com.talentmatch.ai.AiCircuitBreaker;
import com.talentmatch.ai.LocalModelGate;
import com.talentmatch.application.content.DocumentKind;
import com.talentmatch.support.AbstractGenerationIT;
import com.talentmatch.support.Api.Res;
import com.talentmatch.support.SeekerReplies;
import com.talentmatch.support.TestPdfs;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Phase 7 §9.2 item 7: a generation on the local model holds {@link LocalModelGate} exclusively. While the
 * fake blocks a generation, owner-match explanations are AI_BUSY (template, no call) and a CV extraction
 * waits; after release both proceed.
 */
class GenerationGateIT extends AbstractGenerationIT {

    private static final String UPLOAD = "/api/profile/resume";

    @Autowired
    private LocalModelGate gate;

    private JsonNode ownerMatches() {
        Res r = api.get("/api/profile/matches");
        assertThat(r.status()).as(r.toString()).isEqualTo(200);
        return r.json();
    }

    private String resumeStatus(String id) {
        return api.get(UPLOAD + "/" + id).json().get("status").asText();
    }

    @Test
    void aBlockedGenerationMakesExplanationsAiBusyAndAnExtractionWait() throws Exception {
        assertThat(tailoringModel.local()).isTrue();
        saveProfile();
        SeekerReplies.install(fake);
        CountDownLatch release = tailor.block(DocumentKind.CV);
        JsonNode app = createGenerating(initechJob());
        UUID appId = id(app);
        assertThat(tailor.awaitStarted(10)).isTrue();
        String resumeId;
        try {
            assertThat(gate.exclusiveHeld()).as("the generation holds the local model").isTrue();

            JsonNode page = ownerMatches();
            assertThat(page.get("matches").size()).isPositive();
            assertThat(reasons(page)).containsOnly("AI_BUSY");
            page.get("matches").forEach(m ->
                    assertThat(m.at("/explanation/source").asText()).isEqualTo("TEMPLATE"));
            assertThat(fake.calls()).as("no explanation call while the local model is busy").isZero();

            Res up = api.upload(UPLOAD, "file", "cv.pdf", "application/pdf", TestPdfs.cv());
            assertThat(up.status()).as(up.toString()).isEqualTo(202);
            resumeId = up.json().get("id").asText();
            // the extraction is waiting for the gate: no model call, not finished
            await().during(Duration.ofSeconds(1)).atMost(Duration.ofSeconds(3))
                    .until(() -> extractor.calls() == 0 && !"SUCCEEDED".equals(resumeStatus(resumeId)));
        } finally {
            release.countDown();
        }

        awaitGeneration(appId, generationId(app), "SUCCEEDED");
        await().atMost(Duration.ofSeconds(20)).until(() -> "SUCCEEDED".equals(resumeStatus(resumeId)));
        assertThat(extractor.calls()).isEqualTo(1);
        awaitAiIdle();
        assertThat(gate.exclusiveHeld()).isFalse();
        assertThat(circuit.state()).as("AI_BUSY is not a provider failure").isEqualTo(AiCircuitBreaker.State.CLOSED);

        JsonNode after = ownerMatches();
        assertThat(reasons(after)).doesNotContain("AI_BUSY");
        assertThat(fake.calls()).as("explanations are generated again").isPositive();
    }
}
