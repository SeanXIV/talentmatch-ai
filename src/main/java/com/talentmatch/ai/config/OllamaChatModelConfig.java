package com.talentmatch.ai.config;

import com.talentmatch.ai.AiProperties;
import com.talentmatch.ai.LocalModelGate;
import com.talentmatch.application.tailoring.ApplicationProperties;
import com.talentmatch.application.tailoring.TailoringModel;
import com.talentmatch.feed.skills.JobSkillExtractionModel;
import com.talentmatch.feed.skills.SkillsProperties;
import com.talentmatch.profile.ProfileProperties;
import com.talentmatch.profile.ResumeExtractionModel;
import dev.langchain4j.model.chat.Capability;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.ollama.OllamaChatModel;
import java.time.Duration;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Local Ollama chat model (default provider). No network access at construction: the app starts
 * and serves template explanations even when Ollama is not running.
 *
 * <p>Both models send the same {@code num_ctx} ({@code talentmatch.ai.ollama.context-tokens}):
 * a different value per request makes Ollama reload the whole model.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnBooleanProperty(name = "talentmatch.ai.enabled", matchIfMissing = true)
@ConditionalOnProperty(prefix = "talentmatch.ai", name = "provider", havingValue = "ollama", matchIfMissing = true)
public class OllamaChatModelConfig {

    /** One local model on a CPU: a CV extraction holds it exclusively, explanations then get AI_BUSY. */
    @Bean
    public LocalModelGate localModelGate() {
        return new LocalModelGate();
    }

    @Bean
    public ChatModel ollamaChatModel(AiProperties ai) {
        AiProperties.Ollama o = ai.ollama();
        return build(o, ai.callTimeout(), o.maxOutputTokens(), o.temperature());
    }

    /** Same model for CV extraction: long timeout, large output, temperature for faithful copying. */
    @Bean
    public ResumeExtractionModel ollamaResumeExtractionModel(AiProperties ai, ProfileProperties profile) {
        requireContextBudget(ai.ollama(), profile);
        ProfileProperties.Extraction x = profile.extraction();
        return new ResumeExtractionModel(build(ai.ollama(), x.callTimeout(), x.maxOutputTokens(), x.temperature()),
                ModelInfo.of(ai).label(), ai.ollama().contextTokens(), true);
    }

    /** Same model for job-skill enrichment (§4.7): its own timeout and output budget, same num_ctx. */
    @Bean
    public JobSkillExtractionModel ollamaJobSkillExtractionModel(AiProperties ai, SkillsProperties skills) {
        SkillsProperties.Enrichment e = skills.enrichment();
        if (e.enabled()) {                                  // disabled: the model is never called
            requireEnrichmentBudget(ai.ollama(), skills);
        }
        return new JobSkillExtractionModel(build(ai.ollama(), e.callTimeout(), e.maxOutputTokens(), e.temperature()),
                ModelInfo.of(ai).label(), ai.ollama().contextTokens(), true);
    }

    /**
     * Same model for tailored CVs and cover letters (Phase 7): the generation timeout, output budget and
     * temperature, same num_ctx. Local, so the profile stays on this machine (decision c).
     */
    @Bean
    public TailoringModel ollamaTailoringModel(AiProperties ai, ApplicationProperties applications) {
        requireTailoringBudget(ai.ollama(), applications);
        ApplicationProperties.Generation g = applications.generation();
        return new TailoringModel(build(ai.ollama(), g.callTimeout(), g.maxOutputTokens(), g.temperature()),
                ModelInfo.of(ai).label(), ai.ollama().contextTokens(), true);
    }

    /**
     * Fails startup when the context window cannot hold the profile and posting budgets plus prompt and
     * answer: Ollama would otherwise silently drop part of the profile.
     */
    static void requireTailoringBudget(AiProperties.Ollama ollama, ApplicationProperties applications) {
        int required = applications.requiredContextTokens();
        if (required <= ollama.contextTokens()) {
            return;
        }
        ApplicationProperties.Generation g = applications.generation();
        throw new AiConfigurationException(
                "talentmatch.ai.ollama.context-tokens is " + ollama.contextTokens() + ", but tailoring needs at "
                        + "least " + required + " ((talentmatch.applications.generation.max-profile-chars "
                        + g.maxProfileChars() + " + talentmatch.applications.generation.max-job-chars "
                        + g.maxJobChars() + ") / " + ApplicationProperties.CHARS_PER_TOKEN + " + "
                        + ApplicationProperties.PROMPT_OVERHEAD_TOKENS
                        + " prompt + talentmatch.applications.generation.max-output-tokens "
                        + g.maxOutputTokens() + ").",
                "Raise talentmatch.ai.ollama.context-tokens to at least " + required + " (uses more memory), or "
                        + "lower talentmatch.applications.generation.max-profile-chars, "
                        + "talentmatch.applications.generation.max-job-chars or "
                        + "talentmatch.applications.generation.max-output-tokens.");
    }

    /** Fails startup when the context window cannot hold the longest posting text plus prompt and answer. */
    static void requireEnrichmentBudget(AiProperties.Ollama ollama, SkillsProperties skills) {
        int required = skills.requiredContextTokens();
        if (required <= ollama.contextTokens()) {
            return;
        }
        throw new AiConfigurationException(
                "talentmatch.ai.ollama.context-tokens is " + ollama.contextTokens() + ", but job-skill enrichment "
                        + "needs at least " + required + " (talentmatch.feed.skills.max-description-chars "
                        + skills.maxDescriptionChars() + " / " + SkillsProperties.CHARS_PER_TOKEN + " + "
                        + SkillsProperties.PROMPT_OVERHEAD_TOKENS + " prompt + "
                        + "talentmatch.feed.skills.enrichment.max-output-tokens "
                        + skills.enrichment().maxOutputTokens() + ").",
                "Raise talentmatch.ai.ollama.context-tokens to at least " + required + ", or lower "
                        + "talentmatch.feed.skills.max-description-chars or "
                        + "talentmatch.feed.skills.enrichment.max-output-tokens.");
    }

    /**
     * Fails startup when the context window cannot hold the longest CV text plus prompt and
     * output: Ollama would otherwise silently drop part of the CV.
     */
    static void requireContextBudget(AiProperties.Ollama ollama, ProfileProperties profile) {
        int required = profile.requiredContextTokens();
        if (required <= ollama.contextTokens()) {
            return;
        }
        throw new AiConfigurationException(
                "talentmatch.ai.ollama.context-tokens is " + ollama.contextTokens() + ", but CV extraction needs at "
                        + "least " + required + " (talentmatch.profile.max-text-chars " + profile.maxTextChars() + " / "
                        + ProfileProperties.CHARS_PER_TOKEN + " + " + ProfileProperties.PROMPT_OVERHEAD_TOKENS
                        + " prompt + talentmatch.profile.extraction.max-output-tokens "
                        + profile.extraction().maxOutputTokens() + ").",
                "Raise talentmatch.ai.ollama.context-tokens to at least " + required + " (uses more memory), or "
                        + "lower talentmatch.profile.max-text-chars or talentmatch.profile.extraction.max-output-tokens.");
    }

    private static ChatModel build(AiProperties.Ollama o, Duration timeout, int maxOutputTokens, double temperature) {
        return OllamaChatModel.builder()
                .baseUrl(o.baseUrl())
                .modelName(o.model())
                .temperature(temperature)
                .numPredict(maxOutputTokens)
                .numCtx(o.contextTokens())
                .timeout(timeout)
                .maxRetries(0)
                // Ollama's "format" accepts a JSON schema: structured output is requested per call.
                .supportedCapabilities(Set.of(Capability.RESPONSE_FORMAT_JSON_SCHEMA))
                .logRequests(false)
                .logResponses(false)
                .build();
    }
}
