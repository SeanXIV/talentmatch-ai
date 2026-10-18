package com.talentmatch.ai.config;

import com.talentmatch.ai.AiCircuitBreaker;
import com.talentmatch.ai.AiProperties;
import com.talentmatch.ai.ExplanationAssistant;
import com.talentmatch.ai.ExplanationGenerator;
import com.talentmatch.ai.ExplanationGroundingCheck;
import com.talentmatch.ai.LocalModelGate;
import com.talentmatch.ai.MatchExplanationValidator;
import com.talentmatch.ai.SeekerExplanationAssistant;
import com.talentmatch.repository.MatchJdbcRepository;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.AiServices;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.concurrent.ThreadPoolExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * AI explanation wiring. We use LangChain4j core modules with our own beans instead of the
 * Spring Boot starters (beta-only, and their auto-config cannot honour our master switch or fail
 * softly). Every AI bean is conditional on {@code talentmatch.ai.enabled} (default true); the
 * ChatModel comes from exactly one provider config, selected by {@code talentmatch.ai.provider}.
 * Nothing here touches the network at startup.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AiProperties.class)
@Import({OllamaChatModelConfig.class, OpenAiChatModelConfig.class, ClaudeChatModelConfig.class})
public class AiConfiguration {

    private static final Logger log = LoggerFactory.getLogger(AiConfiguration.class);

    public static final String AI_EXECUTOR = "aiExecutor";
    private static final String ENABLED = "talentmatch.ai.enabled";

    /** Unconditional so tests can replace it with a fixed or controllable clock. */
    @Bean
    @ConditionalOnMissingBean
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    @ConditionalOnBooleanProperty(name = ENABLED, matchIfMissing = true)
    public ModelInfo modelInfo(AiProperties properties) {
        return ModelInfo.of(properties);
    }

    /**
     * Bounded pool for model calls: core = max = max-concurrency, queue = queue-capacity, abort
     * when full (the caller then shows AI_BUSY). Running calls are not awaited on shutdown.
     */
    @Bean(AI_EXECUTOR)
    @ConditionalOnBooleanProperty(name = ENABLED, matchIfMissing = true)
    public ThreadPoolTaskExecutor aiExecutor(AiProperties properties) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(properties.maxConcurrency());
        executor.setMaxPoolSize(properties.maxConcurrency());
        executor.setQueueCapacity(properties.queueCapacity());
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setThreadNamePrefix("ai-");
        executor.setWaitForTasksToCompleteOnShutdown(false);
        executor.setAwaitTerminationSeconds(5);
        executor.setTaskDecorator(new MdcTaskDecorator());
        return executor;
    }

    /** Stateless AI service: no chat memory, no tools; safe to share across threads. */
    @Bean
    @ConditionalOnBooleanProperty(name = ENABLED, matchIfMissing = true)
    public ExplanationAssistant explanationAssistant(ChatModel chatModel) {
        return AiServices.builder(ExplanationAssistant.class)
                .chatModel(chatModel)
                .build();
    }

    /**
     * Seeker-facing AI service (Phase 6): a second proxy over the same {@code ChatModel} bean, with its
     * own system prompt. Stateless (no chat memory, no tools), so it shares nothing with
     * {@link #explanationAssistant} except the model client.
     */
    @Bean
    @ConditionalOnBooleanProperty(name = ENABLED, matchIfMissing = true)
    public SeekerExplanationAssistant seekerExplanationAssistant(ChatModel chatModel) {
        return AiServices.builder(SeekerExplanationAssistant.class)
                .chatModel(chatModel)
                .build();
    }

    /**
     * The grounding check comes from outside {@code ai} ({@code seeker.SeekerGroundingCheck}); if no
     * unique bean is available, every SEEKER output is rejected (fail closed).
     */
    @Bean
    @ConditionalOnBooleanProperty(name = ENABLED, matchIfMissing = true)
    public ExplanationGenerator explanationGenerator(ExplanationAssistant assistant,
                                                     SeekerExplanationAssistant seekerAssistant,
                                                     ObjectProvider<ExplanationGroundingCheck> groundingCheck,
                                                     MatchJdbcRepository matchJdbcRepository,
                                                     AiCircuitBreaker circuitBreaker,
                                                     ModelInfo modelInfo,
                                                     @Qualifier(AI_EXECUTOR) ThreadPoolTaskExecutor aiExecutor,
                                                     Clock clock,
                                                     ObjectProvider<MeterRegistry> meterRegistry,
                                                     ObjectProvider<LocalModelGate> localModelGate) {
        ExplanationGroundingCheck check = groundingCheck.getIfUnique();
        if (check == null) {
            log.warn("No unique ExplanationGroundingCheck bean: seeker explanations will always use templates");
        }
        return new ExplanationGenerator(assistant, seekerAssistant, check, new MatchExplanationValidator(),
                matchJdbcRepository, circuitBreaker, modelInfo, aiExecutor, clock, meterRegistry.getIfAvailable(),
                localModelGate.getIfAvailable());
    }
}
