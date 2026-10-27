package com.talentmatch.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.autoconfigure.validation.ValidationAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.validation.BindValidationException;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.yaml.snakeyaml.Yaml;

/** talentmatch.matches.rescore-chunk (Phase 6): default 200, accepted range 1..1000. */
class MatchPropertiesTest {

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(MatchProperties.class)
    static class PropsConfig {
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class,
                    ValidationAutoConfiguration.class))
            .withUserConfiguration(PropsConfig.class);

    @Test
    void defaults() {
        runner.run(ctx -> {
            assertThat(ctx).hasNotFailed();
            MatchProperties m = ctx.getBean(MatchProperties.class);
            assertThat(m.rescoreChunk()).isEqualTo(200);
            assertThat(m.defaultLimit()).isEqualTo(10);
            assertThat(m.maxLimit()).isEqualTo(100);
        });
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 200, 999, 1000})
    void acceptedValues(int chunk) {
        runner.withPropertyValues("talentmatch.matches.rescore-chunk=" + chunk).run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx.getBean(MatchProperties.class).rescoreChunk()).isEqualTo(chunk);
        });
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, 1001, 5000})
    void outOfRangeFailsStartup(int chunk) {
        runner.withPropertyValues("talentmatch.matches.rescore-chunk=" + chunk).run(ctx -> {
            assertThat(ctx).hasFailed();
            assertThat(ctx.getStartupFailure()).rootCause().isInstanceOf(BindValidationException.class)
                    .hasMessageContaining("rescoreChunk");
        });
    }

    @Test
    void nonNumericFailsStartup() {
        runner.withPropertyValues("talentmatch.matches.rescore-chunk=lots").run(ctx -> assertThat(ctx).hasFailed());
    }

    @Test
    void rescoreChunkIsIndependentOfTheLimits() {
        runner.withPropertyValues("talentmatch.matches.rescore-chunk=1", "talentmatch.matches.default-limit=1000",
                "talentmatch.matches.max-limit=1000").run(ctx -> assertThat(ctx).hasNotFailed());
    }

    @Test
    @SuppressWarnings("unchecked")
    void applicationYmlSets200() throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/application.yml")) {
            Map<String, Object> root = new Yaml().loadAll(in).iterator().next() instanceof Map<?, ?> m
                    ? (Map<String, Object>) m : Map.of();
            Map<String, Object> matches = (Map<String, Object>) ((Map<String, Object>) root.get("talentmatch"))
                    .get("matches");
            assertThat(matches.get("rescore-chunk")).isEqualTo(200);
        }
    }
}
