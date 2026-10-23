package com.talentmatch.application.tailoring;

import static org.assertj.core.api.Assertions.assertThat;

import com.talentmatch.ai.FailureKind;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

/** §9.1.13 (GenerationFailure part): of(FailureKind), perKind, tags and hints (§6). */
class GenerationFailureTest {

    @Test
    void ofMapsEveryFailureKindOneToOne() {
        assertThat(GenerationFailure.of(FailureKind.TIMEOUT)).isEqualTo(GenerationFailure.TIMEOUT);
        assertThat(GenerationFailure.of(FailureKind.PROVIDER_ERROR)).isEqualTo(GenerationFailure.PROVIDER_ERROR);
        assertThat(GenerationFailure.of(FailureKind.REFUSED)).isEqualTo(GenerationFailure.REFUSED);
        assertThat(GenerationFailure.of(FailureKind.INVALID_OUTPUT)).isEqualTo(GenerationFailure.INVALID_OUTPUT);
        for (FailureKind k : FailureKind.values()) {
            assertThat(GenerationFailure.of(k).name()).isEqualTo(k.name());
            assertThat(GenerationFailure.of(k).perKind()).isTrue();
        }
    }

    @Test
    void perKindValues() {
        assertThat(Arrays.stream(GenerationFailure.values()).filter(GenerationFailure::perKind)).containsExactly(
                GenerationFailure.TIMEOUT, GenerationFailure.PROVIDER_ERROR, GenerationFailure.REFUSED,
                GenerationFailure.INVALID_OUTPUT, GenerationFailure.CONTEXT_OVERFLOW);
        assertThat(GenerationFailure.values()).extracting(Enum::name).containsExactly("AI_DISABLED", "TIMEOUT",
                "PROVIDER_ERROR", "REFUSED", "INVALID_OUTPUT", "QUEUE_FULL", "CONTEXT_OVERFLOW", "TOO_MANY_ATTEMPTS",
                "REMOTE_GENERATION_DISABLED", "JOB_DELETED");
    }

    @Test
    void tagsAndHints() {
        assertThat(GenerationFailure.INVALID_OUTPUT.tag()).isEqualTo("invalid_output");
        assertThat(GenerationFailure.REMOTE_GENERATION_DISABLED.tag()).isEqualTo("remote_generation_disabled");
        for (GenerationFailure f : GenerationFailure.values()) {
            assertThat(f.hint()).as("%s", f).isNotBlank().endsWith(".");
        }
        assertThat(GenerationFailure.AI_DISABLED.hint()).contains("talentmatch.ai.enabled");
        assertThat(GenerationFailure.TIMEOUT.hint()).contains("talentmatch.applications.generation.call-timeout");
        assertThat(GenerationFailure.PROVIDER_ERROR.hint()).contains("ollama serve");
        assertThat(GenerationFailure.CONTEXT_OVERFLOW.hint()).contains("talentmatch.ai.ollama.context-tokens",
                "max-profile-chars");
        assertThat(GenerationFailure.TOO_MANY_ATTEMPTS.hint()).contains("3 times",
                "POST /api/applications/{id}/generations");
        assertThat(GenerationFailure.REMOTE_GENERATION_DISABLED.hint())
                .contains("talentmatch.applications.allow-remote-generation=true");
        assertThat(GenerationFailure.MAX_ATTEMPTS).isEqualTo(3);
    }
}
