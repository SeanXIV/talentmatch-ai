package com.talentmatch.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.talentmatch.ai.LocalModelGate;
import com.talentmatch.application.tailoring.GenerationFailure;
import com.talentmatch.application.tailoring.TailoringModel;
import com.talentmatch.support.AbstractGenerationIT;
import com.talentmatch.support.FakeTailoringModel;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.convention.TestBean;

/**
 * Phase 7 §9.2 item 5 (decision c): a hosted (remote) TailoringModel without
 * {@code talentmatch.applications.allow-remote-generation} fails the generation with
 * REMOTE_GENERATION_DISABLED and never calls the model. Its own context: the test's TailoringModel is
 * replaced by a remote one.
 */
class GenerationRemoteIT extends AbstractGenerationIT {

    private static final String REMOTE_LABEL = "claude/remote-test";

    @TestBean(name = "fakeTailoringModel", methodName = "remoteTailoringModel")
    private TailoringModel remote;

    @Autowired
    private LocalModelGate gate;

    static TailoringModel remoteTailoringModel() {
        return TailoringModel.remote(new FakeTailoringModel(), REMOTE_LABEL);
    }

    @Test
    void aRemoteModelWithoutTheOptInIsRemoteGenerationDisabledWithNoCall() {
        assertThat(tailoringModel.local()).isFalse();
        saveProfile();
        JsonNode app = createGenerating(initechJob());
        UUID appId = id(app);
        assertThat(app.at("/latestGeneration/status").asText()).isEqualTo("PENDING");

        JsonNode failed = awaitGeneration(appId, generationId(app), "FAILED");
        assertThat(failed.get("failureReason").asText()).isEqualTo("REMOTE_GENERATION_DISABLED");
        assertThat(failed.get("hint").asText()).isEqualTo(GenerationFailure.REMOTE_GENERATION_DISABLED.hint())
                .contains("talentmatch.applications.allow-remote-generation=true");
        assertThat(failed.get("model").asText()).isEqualTo(REMOTE_LABEL);
        assertThat(failed.get("results").size()).isZero();
        assertThat(failed.get("profileVersion").isNull()).as("the profile was never read for a prompt").isTrue();
        awaitAiIdle();
        assertThat(tailor.calls()).as("the profile never left the machine").isZero();
        assertThat(tailor.requests()).isEmpty();
        assertThat(documentRows(appId)).isEmpty();
        assertThat(gate.exclusiveHeld()).isFalse();

        // a second request fails the same way
        JsonNode again = postGeneration(appId, "{\"kinds\": [\"COVER_LETTER\"]}").json();
        assertThat(awaitGeneration(appId, id(again), "FAILED").get("failureReason").asText())
                .isEqualTo("REMOTE_GENERATION_DISABLED");
        assertThat(tailor.calls()).isZero();
    }
}
