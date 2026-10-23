package com.talentmatch.feed.skills;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/** §9.1 item 15 (enrichment part): ranges and the context budget. */
class SkillsPropertiesTest {

    @Test
    void defaultsAndBudget() {
        SkillsProperties p = SkillsProperties.defaults();
        assertThat(p.maxDescriptionChars()).isEqualTo(8000);
        assertThat(p.enrichment().maxAttempts()).isEqualTo(3);
        assertThat(p.enrichment().maxPerHour()).isEqualTo(30);
        assertThat(p.requiredContextTokens()).isEqualTo(2667 + 800 + 600);
    }

    @Test
    void outOfRangeValuesNameTheProperty() {
        assertThatThrownBy(() -> new SkillsProperties(100, null))
                .hasMessageContaining("talentmatch.feed.skills.max-description-chars");
        assertThatThrownBy(() -> new SkillsProperties.Enrichment(true, Duration.ofMinutes(5), 50, 0, 3,
                Duration.ofMinutes(5), Duration.ofSeconds(60), 30, Duration.ofSeconds(60)))
                .hasMessageContaining("enrichment.max-output-tokens");
        assertThatThrownBy(() -> new SkillsProperties.Enrichment(true, Duration.ofMinutes(5), 600, 3.5, 3,
                Duration.ofMinutes(5), Duration.ofSeconds(60), 30, Duration.ofSeconds(60)))
                .hasMessageContaining("enrichment.temperature");
        assertThatThrownBy(() -> new SkillsProperties.Enrichment(true, Duration.ofHours(2), 600, 0, 3,
                Duration.ofMinutes(5), Duration.ofSeconds(60), 30, Duration.ofSeconds(60)))
                .hasMessageContaining("enrichment.call-timeout");
        assertThatThrownBy(() -> new SkillsProperties.Enrichment(true, Duration.ofMinutes(5), 600, 0, 0,
                Duration.ofMinutes(5), Duration.ofSeconds(60), 30, Duration.ofSeconds(60)))
                .hasMessageContaining("enrichment.max-attempts");
    }
}
