package com.talentmatch.profile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

/** Phase 4: talentmatch.profile.* binding, defaults and bounds (B2, B4, N6). */
class ProfilePropertiesTest {

    private static ProfileProperties bind(Map<String, String> values) {
        return new Binder(new MapConfigurationPropertySource(values))
                .bindOrCreate("talentmatch.profile", ProfileProperties.class);
    }

    @Test
    void defaults() {
        ProfileProperties p = bind(Map.of());
        assertThat(p.maxResumeBytes()).isEqualTo(5 * 1024 * 1024);
        assertThat(p.maxTextChars()).isEqualTo(16_000);
        assertThat(p.extraction().callTimeout()).as("B4").isEqualTo(Duration.ofMinutes(60));
        assertThat(p.extraction().maxOutputTokens()).isEqualTo(4096);
        assertThat(p.extraction().temperature()).as("N6").isEqualTo(0.0);
        assertThat(p.requiredContextTokens()).isEqualTo(10_430);
        assertThat(p.allowRemoteExtraction()).as("the CV stays on the machine unless opted in").isFalse();
        assertThat(bind(Map.of("talentmatch.profile.allow-remote-extraction", "true")).allowRemoteExtraction()).isTrue();
    }

    @Test
    void nullExtractionGetsTheSameDefaults() {
        ProfileProperties p = new ProfileProperties(5_242_880, 16_000, null, false);
        assertThat(p.extraction()).isEqualTo(bind(Map.of()).extraction());
    }

    @Test
    void timeoutBounds() {
        assertThat(bind(Map.of("talentmatch.profile.extraction.call-timeout", "1s")).extraction().callTimeout())
                .isEqualTo(Duration.ofSeconds(1));
        assertThat(bind(Map.of("talentmatch.profile.extraction.call-timeout", "2h")).extraction().callTimeout())
                .isEqualTo(Duration.ofHours(2));
        for (String bad : new String[] {"0s", "500ms", "3h", "121m"}) {
            assertThatThrownBy(() -> bind(Map.of("talentmatch.profile.extraction.call-timeout", bad)))
                    .as(bad).isInstanceOf(BindException.class)
                    .rootCause().hasMessageContaining("call-timeout").hasMessageContaining("between PT1S and PT2H");
        }
    }

    @Test
    void contextBudgetFormula() {
        ProfileProperties big = new ProfileProperties(5_242_880, 24_000, null, false);
        assertThat(big.requiredContextTokens()).as("24000/3 + 1000 + 4096").isEqualTo(13_096);
        ProfileProperties odd = new ProfileProperties(5_242_880, 1_001, null, false);
        assertThat(odd.requiredContextTokens()).as("ceil(1001/3)=334").isEqualTo(334 + 1000 + 4096);
    }

    @Test
    void customValuesBind() {
        ProfileProperties p = bind(Map.of("talentmatch.profile.max-text-chars", "8000",
                "talentmatch.profile.extraction.max-output-tokens", "2048",
                "talentmatch.profile.extraction.temperature", "0.1"));
        assertThat(p.maxTextChars()).isEqualTo(8000);
        assertThat(p.extraction().maxOutputTokens()).isEqualTo(2048);
        assertThat(p.extraction().temperature()).isEqualTo(0.1);
        assertThat(p.extraction().callTimeout()).isEqualTo(Duration.ofMinutes(60));
    }
}
