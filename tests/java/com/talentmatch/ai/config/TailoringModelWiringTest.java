package com.talentmatch.ai.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.talentmatch.ai.AiCircuitBreaker;
import com.talentmatch.ai.AiHealthIndicator;
import com.talentmatch.ai.AiProperties;
import com.talentmatch.ai.ExplanationRateLimiter;
import com.talentmatch.ai.ExplanationService;
import com.talentmatch.application.tailoring.ApplicationProperties;
import com.talentmatch.application.tailoring.TailoringModel;
import com.talentmatch.config.ProfileConfig;
import com.talentmatch.feed.skills.SkillsProperties;
import com.talentmatch.repository.MatchJdbcRepository;
import dev.langchain4j.model.anthropic.AnthropicChatModel;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.ollama.OllamaChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.diagnostics.FailureAnalysis;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** Phase 7 step 4: one TailoringModel per provider, still one ChatModel bean, and requireTailoringBudget. */
class TailoringModelWiringTest {

    @EnableConfigurationProperties(SkillsProperties.class)
    static class SkillsConfig {
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(AiConfiguration.class, AiCircuitBreaker.class, ExplanationRateLimiter.class,
                    ExplanationService.class, AiHealthIndicator.class, ProfileConfig.class, SkillsConfig.class)
            .withBean(MatchJdbcRepository.class, () -> mock(MatchJdbcRepository.class))
            .withPropertyValues("talentmatch.ai.ollama.base-url=http://localhost:1");

    @Test
    void ollamaHasALocalTailoringModelSharingNumCtx() {
        runner.run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx.getBeansOfType(ChatModel.class)).hasSize(1);
            TailoringModel m = ctx.getBean(TailoringModel.class);
            assertThat(m.chatModel()).isInstanceOf(OllamaChatModel.class).isNotSameAs(ctx.getBean(ChatModel.class));
            assertThat(m.label()).isEqualTo("ollama/qwen2.5:7b-instruct");
            assertThat(m.contextTokens()).isEqualTo(12288);
            assertThat(m.local()).isTrue();
            assertThat(ctx.getBean(ApplicationProperties.class)).isEqualTo(ApplicationProperties.defaults());
        });
    }

    @Test
    void hostedProvidersHaveARemoteTailoringModel() {
        runner.withPropertyValues("talentmatch.ai.provider=openai", "talentmatch.ai.openai.api-key=sk-test-123")
                .run(ctx -> {
                    assertThat(ctx.getBeansOfType(ChatModel.class)).hasSize(1);
                    TailoringModel m = ctx.getBean(TailoringModel.class);
                    assertThat(m.chatModel()).isInstanceOf(OpenAiChatModel.class);
                    assertThat(m.local()).isFalse();
                    assertThat(m.contextTokens()).isNull();
                });
        runner.withPropertyValues("talentmatch.ai.provider=claude", "talentmatch.ai.claude.api-key=sk-ant-test")
                .run(ctx -> {
                    TailoringModel m = ctx.getBean(TailoringModel.class);
                    assertThat(m.chatModel()).isInstanceOf(AnthropicChatModel.class);
                    assertThat(m.local()).isFalse();
                });
        runner.withPropertyValues("talentmatch.ai.enabled=false")
                .run(ctx -> assertThat(ctx).doesNotHaveBean(TailoringModel.class));
    }

    @Test
    void ollamaContextTooSmallForTheTailoringBudgetFailsStartup() {
        // small CV budget (334 + 1000 + 4096 = 5430) so only tailoring (8582) is over 8192
        runner.withPropertyValues("talentmatch.ai.ollama.context-tokens=8192", "talentmatch.profile.max-text-chars=1000")
                .run(ctx -> {
                    assertThat(ctx).hasFailed();
                    FailureAnalysis a = new AiStartupFailureAnalyzer().analyze(ctx.getStartupFailure());
                    assertThat(a).isNotNull();
                    assertThat(a.getDescription()).contains("context-tokens is 8192", "tailoring", "8582");
                    assertThat(a.getAction()).contains("Raise talentmatch.ai.ollama.context-tokens to at least 8582");
                });
        runner.withPropertyValues("talentmatch.ai.ollama.context-tokens=8582", "talentmatch.profile.max-text-chars=1000")
                .run(ctx -> assertThat(ctx).hasNotFailed());
        runner.withPropertyValues("talentmatch.ai.ollama.context-tokens=8192", "talentmatch.profile.max-text-chars=1000",
                        "talentmatch.applications.generation.max-profile-chars=10000")
                .run(ctx -> assertThat(ctx).as("(10000 + 4000) / 3 + 1200 + 2048 = 7915 fits").hasNotFailed());
    }

    @Test
    void theCvBudgetIsStillReportedFirst() {
        runner.withPropertyValues("talentmatch.ai.ollama.context-tokens=8192").run(ctx -> {
            assertThat(ctx).hasFailed();
            FailureAnalysis a = new AiStartupFailureAnalyzer().analyze(ctx.getStartupFailure());
            assertThat(a.getDescription()).contains("CV extraction").doesNotContain("tailoring");
        });
    }

    @Test
    void hostedProvidersSkipTheTailoringBudget() {
        runner.withPropertyValues("talentmatch.ai.provider=openai", "talentmatch.ai.openai.api-key=sk-test-123",
                "talentmatch.applications.generation.max-profile-chars=60000").run(ctx -> assertThat(ctx).hasNotFailed());
    }

    @Test
    void requireTailoringBudgetDirectly() {
        AiProperties.Ollama small = new AiProperties.Ollama(null, null, 0.2, 400, 8192);
        assertThatThrownBy(() -> OllamaChatModelConfig.requireTailoringBudget(small, ApplicationProperties.defaults()))
                .isInstanceOf(AiConfigurationException.class)
                .hasMessageContaining("talentmatch.ai.ollama.context-tokens is 8192, but tailoring needs at least 8582")
                .hasMessageContaining("max-profile-chars 12000").hasMessageContaining("max-job-chars 4000")
                .hasMessageContaining("max-output-tokens 2048");
        OllamaChatModelConfig.requireTailoringBudget(new AiProperties.Ollama(null, null, 0.2, 400, 8582),
                ApplicationProperties.defaults());
        ApplicationProperties big = new ApplicationProperties(false, null, new ApplicationProperties.Generation(
                Duration.ofMinutes(45), 16384, 0.2, 60000, 20000), null);
        assertThatThrownBy(() -> OllamaChatModelConfig.requireTailoringBudget(
                new AiProperties.Ollama(null, null, 0.2, 400, AiProperties.Ollama.DEFAULT_CONTEXT_TOKENS), big))
                .hasMessageContaining("at least " + big.requiredContextTokens());
    }
}
