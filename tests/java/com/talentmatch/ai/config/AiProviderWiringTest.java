package com.talentmatch.ai.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.talentmatch.ai.AiCircuitBreaker;
import com.talentmatch.ai.AiHealthIndicator;
import com.talentmatch.ai.AiProperties;
import com.talentmatch.ai.ExplanationAssistant;
import com.talentmatch.ai.ExplanationGenerator;
import com.talentmatch.ai.ExplanationRateLimiter;
import com.talentmatch.ai.ExplanationService;
import com.talentmatch.repository.MatchJdbcRepository;
import dev.langchain4j.model.anthropic.AnthropicChatModel;
import dev.langchain4j.model.chat.Capability;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.ollama.OllamaChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import java.time.Clock;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.diagnostics.FailureAnalysis;
import org.springframework.boot.diagnostics.FailureAnalyzer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/** Spec §2 provider wiring / §7 / §10 unit 8. No network: building a model never connects. */
class AiProviderWiringTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(AiConfiguration.class, AiCircuitBreaker.class, ExplanationRateLimiter.class,
                    ExplanationService.class, AiHealthIndicator.class)
            .withBean(MatchJdbcRepository.class, () -> mock(MatchJdbcRepository.class))
            // never reach a real local Ollama
            .withPropertyValues("talentmatch.ai.ollama.base-url=http://localhost:1");

    @Test
    void defaultIsOllamaWithAssistantExecutorAndGenerator() {
        runner.run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx.getBean(ChatModel.class)).isInstanceOf(OllamaChatModel.class);
            assertThat(ctx.getBean(ChatModel.class).supportedCapabilities())
                    .contains(Capability.RESPONSE_FORMAT_JSON_SCHEMA);
            assertThat(ctx).hasSingleBean(ExplanationAssistant.class).hasSingleBean(ExplanationGenerator.class)
                    .hasSingleBean(Clock.class).hasSingleBean(ModelInfo.class);
            assertThat(ctx.getBean(ModelInfo.class).label()).isEqualTo("ollama/qwen2.5:7b-instruct");
            ThreadPoolTaskExecutor ex = ctx.getBean("aiExecutor", ThreadPoolTaskExecutor.class);
            assertThat(ex.getCorePoolSize()).isEqualTo(2);
            assertThat(ex.getMaxPoolSize()).isEqualTo(2);
            assertThat(ex.getQueueCapacity()).isEqualTo(20);
            assertThat(ex.getThreadNamePrefix()).isEqualTo("ai-");
            assertThat(ctx.getBean(ExplanationService.class).aiActive()).isTrue();
            AiProperties p = ctx.getBean(AiProperties.class);
            assertThat(p.topN()).isEqualTo(5);
            assertThat(p.requestBudget()).hasSeconds(8);
            assertThat(p.callTimeout()).hasSeconds(60);
            assertThat(p.claude().model()).isEqualTo("claude-sonnet-5-5");
            assertThat(p.claude().maxTokens()).isEqualTo(3000);
            assertThat(p.claude().effort()).isEmpty();
            assertThat(p.circuit().failureThreshold()).isEqualTo(3);
        });
    }

    @Test
    void openAiWithKey() {
        runner.withPropertyValues("talentmatch.ai.provider=openai", "talentmatch.ai.openai.api-key=sk-test-123")
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx.getBean(ChatModel.class)).isInstanceOf(OpenAiChatModel.class);
                    assertThat(ctx.getBeansOfType(ChatModel.class)).hasSize(1);
                    assertThat(ctx.getBean(ModelInfo.class).label()).isEqualTo("openai/gpt-4.1-mini");
                });
    }

    @Test
    void claudeWithKeyIgnoringCase() {
        for (String provider : List.of("claude", "CLAUDE")) {
            runner.withPropertyValues("talentmatch.ai.provider=" + provider,
                    "talentmatch.ai.claude.api-key=sk-ant-test").run(ctx -> {
                        assertThat(ctx).hasNotFailed();
                        assertThat(ctx.getBean(ChatModel.class)).as(provider).isInstanceOf(AnthropicChatModel.class);
                        assertThat(ctx.getBeansOfType(ChatModel.class)).hasSize(1);
                        assertThat(ctx.getBean(ModelInfo.class).label()).isEqualTo("claude/claude-sonnet-5-5");
                    });
        }
    }

    @Test
    void missingKeysFailStartupWithAnActionableMessage() {
        runner.withPropertyValues("talentmatch.ai.provider=claude").run(ctx -> {
            assertThat(ctx).hasFailed();
            FailureAnalysis a = analyze(ctx.getStartupFailure());
            assertThat(a.getDescription() + " " + a.getAction()).isEqualTo("Profile 'claude' is active but "
                    + "ANTHROPIC_API_KEY is not set. Export it, or drop the profile to use local Ollama (default) "
                    + "or set AI_ENABLED=false.");
        });
        runner.withPropertyValues("talentmatch.ai.provider=openai", "talentmatch.ai.openai.api-key=  ").run(ctx -> {
            assertThat(ctx).hasFailed();
            FailureAnalysis a = analyze(ctx.getStartupFailure());
            assertThat(a.getDescription()).contains("OPENAI_API_KEY").contains("'openai'");
        });
    }

    @Test
    void nonBlankEffortFailsAndBlankEffortIsAccepted() {
        runner.withPropertyValues("talentmatch.ai.provider=claude", "talentmatch.ai.claude.api-key=k",
                "talentmatch.ai.claude.effort=low").run(ctx -> {
                    assertThat(ctx).hasFailed();
                    FailureAnalysis a = analyze(ctx.getStartupFailure());
                    assertThat(a.getDescription()).contains("effort").contains("'low'");
                    assertThat(a.getAction()).contains("blank");
                });
        runner.withPropertyValues("talentmatch.ai.provider=claude", "talentmatch.ai.claude.api-key=k",
                "talentmatch.ai.claude.effort=").run(ctx -> assertThat(ctx).hasNotFailed());
        runner.withPropertyValues("talentmatch.ai.provider=claude", "talentmatch.ai.claude.api-key=k",
                "talentmatch.ai.claude.effort=  ").run(ctx -> assertThat(ctx).hasNotFailed());
    }

    @Test
    void disabledHasNoLlmBeansButServiceHealthAndLimiter() {
        // even with a provider whose key is missing: nothing AI-related is built
        runner.withPropertyValues("talentmatch.ai.enabled=false", "talentmatch.ai.provider=claude").run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx).doesNotHaveBean(ChatModel.class).doesNotHaveBean(ExplanationAssistant.class)
                    .doesNotHaveBean("aiExecutor").doesNotHaveBean(ExplanationGenerator.class)
                    .doesNotHaveBean(ModelInfo.class);
            assertThat(ctx).hasSingleBean(ExplanationService.class).hasSingleBean(AiHealthIndicator.class)
                    .hasSingleBean(ExplanationRateLimiter.class).hasSingleBean(AiCircuitBreaker.class)
                    .hasSingleBean(Clock.class);
            assertThat(ctx.getBean(ExplanationService.class).aiActive()).isFalse();
            assertThat(ctx.getBean(AiHealthIndicator.class).health().getDetails())
                    .containsEntry("mode", "template-only");
        });
    }

    @Test
    void bogusProviderAndOutOfRangeValuesFailBinding() {
        runner.withPropertyValues("talentmatch.ai.provider=bogus").run(ctx -> {
            assertThat(ctx).hasFailed();
            assertThat(chain(ctx.getStartupFailure())).anyMatch(t -> t instanceof BindException);
        });
        for (String bad : List.of("talentmatch.ai.top-n=0", "talentmatch.ai.top-n=21",
                "talentmatch.ai.call-timeout=0s", "talentmatch.ai.request-budget=61s",
                "talentmatch.ai.regenerate-window=0s", "talentmatch.ai.max-context-chars=10",
                "talentmatch.ai.max-concurrency=0", "talentmatch.ai.circuit.failure-threshold=0")) {
            runner.withPropertyValues(bad).run(ctx -> assertThat(ctx).as(bad).hasFailed());
        }
    }

    @Test
    void customClockReplacesTheDefault() {
        Clock fixed = Clock.fixed(java.time.Instant.EPOCH, java.time.ZoneOffset.UTC);
        runner.withBean(Clock.class, () -> fixed).run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx.getBean(Clock.class)).isSameAs(fixed);
        });
    }

    @Test
    void analyzerIsRegisteredInSpringFactories() throws Exception {
        java.util.Properties factories = new java.util.Properties();
        try (var in = getClass().getClassLoader().getResourceAsStream("META-INF/spring.factories")) {
            factories.load(in);
        }
        assertThat(List.of(factories.getProperty(FailureAnalyzer.class.getName()).split("\\s*,\\s*")))
                .contains(AiStartupFailureAnalyzer.class.getName(),
                        "com.talentmatch.config.DatabaseStartupFailureAnalyzer");
    }

    private static FailureAnalysis analyze(Throwable failure) {
        FailureAnalysis a = new AiStartupFailureAnalyzer().analyze(failure);
        assertThat(a).as("analyzer recognises %s", failure).isNotNull();
        return a;
    }

    private static List<Throwable> chain(Throwable t) {
        List<Throwable> out = new java.util.ArrayList<>();
        for (Throwable c = t; c != null && out.size() < 50; c = c.getCause()) {
            out.add(c);
        }
        return out;
    }
}
