package com.talentmatch.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/** Spec §2 AiProperties: compact-ctor range checks and key masking. */
class AiPropertiesTest {

    private static AiProperties with(Duration callTimeout, Duration budget, Duration backoff, Duration window,
                                     AiProperties.OpenAi openai, AiProperties.Claude claude) {
        return new AiProperties(true, AiProvider.OLLAMA, 5, callTimeout, budget, 2, 20, 2000, backoff, window,
                null, null, openai, claude);
    }

    @Test
    void toStringNeverContainsApiKeys() {
        AiProperties p = with(Duration.ofSeconds(60), Duration.ofSeconds(8), Duration.ofSeconds(60),
                Duration.ofSeconds(60), new AiProperties.OpenAi("sk-openai-secret-1", null, null, 0.2, 500),
                new AiProperties.Claude("sk-ant-secret-2", null, null, 3000, "", false));
        assertThat(p.toString()).doesNotContain("sk-openai-secret-1").doesNotContain("sk-ant-secret-2")
                .contains("apiKey=****");
        assertThat(p.openai().toString()).doesNotContain("secret");
        assertThat(p.claude().toString()).doesNotContain("secret");
        assertThat(new AiProperties.Claude(null, null, null, 3000, null, false).toString()).contains("apiKey=(unset)");
    }

    @Test
    void defaultsForNestedRecords() {
        AiProperties p = with(Duration.ofSeconds(60), Duration.ofSeconds(8), Duration.ofSeconds(60),
                Duration.ofSeconds(60), null, null);
        assertThat(p.ollama().baseUrl()).isEqualTo("http://localhost:11434");
        assertThat(p.ollama().model()).isEqualTo("qwen2.5:7b-instruct");
        assertThat(p.ollama().maxOutputTokens()).isEqualTo(400);
        assertThat(p.openai().model()).isEqualTo("gpt-4.1-mini");
        assertThat(p.claude().model()).isEqualTo("claude-sonnet-5-5");
        assertThat(p.claude().effort()).isEmpty();
        assertThat(p.circuit().openDuration()).hasSeconds(30);
        assertThat(p.activeModel()).isEqualTo("qwen2.5:7b-instruct");
    }

    @Test
    void rangeChecksNameTheProperty() {
        Duration s60 = Duration.ofSeconds(60);
        assertThatIllegalArgumentException().isThrownBy(() -> with(Duration.ZERO, s60, s60, s60, null, null))
                .withMessageContaining("talentmatch.ai.call-timeout");
        assertThatIllegalArgumentException().isThrownBy(() -> with(Duration.ofSeconds(301), s60, s60, s60, null, null))
                .withMessageContaining("call-timeout");
        assertThatIllegalArgumentException().isThrownBy(() -> with(s60, Duration.ofSeconds(-1), s60, s60, null, null))
                .withMessageContaining("request-budget");
        assertThatIllegalArgumentException().isThrownBy(() -> with(s60, Duration.ofSeconds(61), s60, s60, null, null))
                .withMessageContaining("request-budget");
        assertThatIllegalArgumentException().isThrownBy(() -> with(s60, s60, Duration.ofSeconds(-1), s60, null, null))
                .withMessageContaining("failure-backoff");
        assertThatIllegalArgumentException().isThrownBy(() -> with(s60, s60, s60, Duration.ofMillis(999), null, null))
                .withMessageContaining("regenerate-window");
        // boundaries are accepted
        with(Duration.ofSeconds(1), Duration.ZERO, Duration.ZERO, Duration.ofSeconds(1), null, null);
        with(Duration.ofSeconds(300), s60, s60, s60, null, null);
    }
}
