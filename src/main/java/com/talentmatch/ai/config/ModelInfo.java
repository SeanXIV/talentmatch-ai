package com.talentmatch.ai.config;

import com.talentmatch.ai.AiProperties;
import com.talentmatch.ai.AiProvider;
import java.util.Objects;

/**
 * The configured provider and model, e.g. label {@code ollama/qwen2.5:7b-instruct}.
 * Stored with every explanation (explanation_model).
 */
public record ModelInfo(AiProvider provider, String modelName) {

    public ModelInfo {
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(modelName, "modelName");
    }

    public static ModelInfo of(AiProperties properties) {
        return new ModelInfo(properties.provider(), properties.activeModel());
    }

    /** {@code provider/model}, e.g. {@code ollama/qwen2.5:7b-instruct}. */
    public String label() {
        return provider.id() + "/" + modelName;
    }
}
