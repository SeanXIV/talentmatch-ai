package com.talentmatch.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.talentmatch.domain.scoring.ScoringEngine;
import com.talentmatch.domain.scoring.ScoringWeights;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.autoconfigure.validation.ValidationAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.validation.BindValidationException;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/** Invalid talentmatch.* settings must fail startup (no database needed). */
class ScoringPropertiesTest {

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties({ScoringProperties.class, MatchProperties.class, RecomputeProperties.class})
    @Import(ScoringConfig.class)
    static class PropsConfig {
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class,
                    ValidationAutoConfiguration.class))
            .withUserConfiguration(PropsConfig.class);

    @Test
    void validWeightsWireTheEngine() {
        runner.withPropertyValues("talentmatch.scoring.required-weight=3",
                        "talentmatch.scoring.nice-to-have-weight=1")
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx.getBean(ScoringEngine.class).weights()).isEqualTo(new ScoringWeights(3, 1));
                    MatchProperties m = ctx.getBean(MatchProperties.class);
                    assertThat(m.defaultLimit()).isEqualTo(10);
                    assertThat(m.maxLimit()).isEqualTo(100);
                    RecomputeProperties r = ctx.getBean(RecomputeProperties.class);
                    assertThat(r.historySize()).isEqualTo(20);
                    assertThat(r.maxFailuresReported()).isEqualTo(50);
                });
    }

    @Test
    void zeroWeightFailsStartup() {
        runner.withPropertyValues("talentmatch.scoring.required-weight=0",
                        "talentmatch.scoring.nice-to-have-weight=5")
                .run(ctx -> {
                    assertThat(ctx).hasFailed();
                    assertThat(ctx.getStartupFailure()).rootCause().isInstanceOf(BindValidationException.class)
                            .hasMessageContaining("requiredWeight");
                });
    }

    @Test
    void tooLargeWeightFailsStartup() {
        runner.withPropertyValues("talentmatch.scoring.required-weight=10",
                        "talentmatch.scoring.nice-to-have-weight=1001")
                .run(ctx -> assertThat(ctx).hasFailed());
    }

    @Test
    void missingWeightsFailStartup() {
        runner.run(ctx -> assertThat(ctx).hasFailed());
    }

    @Test
    void nonNumericWeightFailsStartup() {
        runner.withPropertyValues("talentmatch.scoring.required-weight=ten",
                        "talentmatch.scoring.nice-to-have-weight=5")
                .run(ctx -> assertThat(ctx).hasFailed());
    }

    @Test
    void defaultLimitAboveMaxLimitFailsStartup() {
        runner.withPropertyValues("talentmatch.scoring.required-weight=10",
                        "talentmatch.scoring.nice-to-have-weight=5",
                        "talentmatch.matches.default-limit=50", "talentmatch.matches.max-limit=20")
                .run(ctx -> assertThat(ctx).hasFailed());
    }
}
