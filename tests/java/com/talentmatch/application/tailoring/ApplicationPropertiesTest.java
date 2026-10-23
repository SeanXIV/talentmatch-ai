package com.talentmatch.application.tailoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** §9.1.12: defaults, ranges, min < max, requiredContextTokens; binding through Spring. */
class ApplicationPropertiesTest {

    private static ApplicationProperties.Generation gen(Duration timeout, int out, double temp, int profile, int job) {
        return new ApplicationProperties.Generation(timeout, out, temp, profile, job);
    }

    @Test
    void defaults() {
        ApplicationProperties p = ApplicationProperties.defaults();
        assertThat(p.allowRemoteGeneration()).isFalse();
        assertThat(p.zone()).isEqualTo("Africa/Johannesburg");
        assertThat(p.zoneId()).isEqualTo(ZoneId.of("Africa/Johannesburg"));
        assertThat(p.generation()).isEqualTo(gen(Duration.ofMinutes(45), 2048, 0.2, 12000, 4000));
        assertThat(p.coverLetter()).isEqualTo(new ApplicationProperties.CoverLetter(180, 350));
        assertThat(p.requiredContextTokens()).isEqualTo(8582);
    }

    @Test
    void requiredContextTokensRoundsUp() {
        // (1000 + 500) / 3 = 500 exactly; (1000 + 501) / 3 = 500.33 -> 501
        assertThat(new ApplicationProperties(false, "UTC", gen(Duration.ofMinutes(1), 512, 0, 1000, 500), null)
                .requiredContextTokens()).isEqualTo(500 + 1200 + 512);
        assertThat(new ApplicationProperties(false, "UTC", gen(Duration.ofMinutes(1), 512, 0, 1000, 501), null)
                .requiredContextTokens()).isEqualTo(501 + 1200 + 512);
    }

    @Test
    void generationRanges() {
        Duration t = Duration.ofMinutes(1);
        gen(Duration.ofSeconds(1), 512, 0.0, 1000, 500);
        gen(Duration.ofHours(2), 16384, 2.0, 60000, 20000);
        assertThatThrownBy(() -> gen(Duration.ofMillis(999), 2048, 0.2, 12000, 4000))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("talentmatch.applications.generation.call-timeout is PT0.999S but must be between PT1S and PT2H");
        assertThatThrownBy(() -> gen(Duration.ofHours(2).plusSeconds(1), 2048, 0.2, 12000, 4000))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> gen(null, 2048, 0.2, 12000, 4000)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> gen(t, 511, 0.2, 12000, 4000)).hasMessage(
                "talentmatch.applications.generation.max-output-tokens is 511 but must be between 512 and 16384");
        assertThatThrownBy(() -> gen(t, 16385, 0.2, 12000, 4000)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> gen(t, 2048, -0.1, 12000, 4000)).hasMessageContaining("temperature is -0.1");
        assertThatThrownBy(() -> gen(t, 2048, 2.01, 12000, 4000)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> gen(t, 2048, Double.NaN, 12000, 4000)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> gen(t, 2048, 0.2, 999, 4000)).hasMessageContaining("max-profile-chars is 999");
        assertThatThrownBy(() -> gen(t, 2048, 0.2, 60001, 4000)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> gen(t, 2048, 0.2, 12000, 499)).hasMessageContaining("max-job-chars is 499");
        assertThatThrownBy(() -> gen(t, 2048, 0.2, 12000, 20001)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void coverLetterRangesAndOrder() {
        new ApplicationProperties.CoverLetter(50, 600);
        new ApplicationProperties.CoverLetter(50, 51);
        assertThatThrownBy(() -> new ApplicationProperties.CoverLetter(49, 350))
                .hasMessage("talentmatch.applications.cover-letter.min-words is 49 but must be between 50 and 600");
        assertThatThrownBy(() -> new ApplicationProperties.CoverLetter(180, 601))
                .hasMessage("talentmatch.applications.cover-letter.max-words is 601 but must be between 50 and 600");
        assertThatThrownBy(() -> new ApplicationProperties.CoverLetter(300, 300)).hasMessage(
                "talentmatch.applications.cover-letter.min-words (300) must be less than "
                        + "talentmatch.applications.cover-letter.max-words (300)");
        assertThatThrownBy(() -> new ApplicationProperties.CoverLetter(400, 300))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void zone() {
        assertThat(new ApplicationProperties(false, " UTC ", null, null).zone()).isEqualTo("UTC");
        assertThat(new ApplicationProperties(false, " ", null, null).zone()).isEqualTo("Africa/Johannesburg");
        assertThatThrownBy(() -> new ApplicationProperties(false, "Mars/Olympus", null, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("talentmatch.applications.zone is "
                        + "Mars/Olympus");
    }

    @EnableConfigurationProperties(ApplicationProperties.class)
    static class Config {
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(Config.class);

    @Test
    void bindsFromProperties() {
        runner.run(ctx -> assertThat(ctx.getBean(ApplicationProperties.class)).isEqualTo(ApplicationProperties.defaults()));
        runner.withPropertyValues("talentmatch.applications.allow-remote-generation=true",
                "talentmatch.applications.zone=UTC",
                "talentmatch.applications.generation.call-timeout=10m",
                "talentmatch.applications.generation.max-output-tokens=4096",
                "talentmatch.applications.generation.max-profile-chars=20000",
                "talentmatch.applications.cover-letter.min-words=100",
                "talentmatch.applications.cover-letter.max-words=200").run(ctx -> {
                    ApplicationProperties p = ctx.getBean(ApplicationProperties.class);
                    assertThat(p.allowRemoteGeneration()).isTrue();
                    assertThat(p.zoneId()).isEqualTo(ZoneId.of("UTC"));
                    assertThat(p.generation().callTimeout()).isEqualTo(Duration.ofMinutes(10));
                    assertThat(p.generation().maxOutputTokens()).isEqualTo(4096);
                    assertThat(p.generation().maxJobChars()).isEqualTo(4000);
                    assertThat(p.coverLetter().maxWords()).isEqualTo(200);
                });
        for (String bad : new String[] {"talentmatch.applications.generation.max-output-tokens=100",
                "talentmatch.applications.cover-letter.min-words=400",
                "talentmatch.applications.generation.temperature=3",
                "talentmatch.applications.zone=Nowhere/Land"}) {
            runner.withPropertyValues(bad).run(ctx -> assertThat(ctx).as(bad).hasFailed());
        }
    }
}
