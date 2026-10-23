package com.talentmatch.application.tailoring;

import dev.langchain4j.model.chat.ChatModel;
import java.util.Objects;

/**
 * The provider's model built for tailoring (generation timeout, output budget and temperature). A holder
 * type, so the explanation {@link ChatModel} stays the only ChatModel bean.
 *
 * @param chatModel     the configured model
 * @param label         {@code provider/model}, stored with each generation and AI version
 * @param contextTokens the model's context window when we set it ourselves (Ollama {@code num_ctx});
 *                      null when the provider manages it. Used to detect a prompt that was cut off.
 * @param local         true when the model runs on this machine (Ollama): the profile never leaves it, and
 *                      the call holds {@code LocalModelGate} exclusively. A remote model (OpenAI, Claude) is
 *                      only called with {@code talentmatch.applications.allow-remote-generation=true}.
 */
public record TailoringModel(ChatModel chatModel, String label, Integer contextTokens, boolean local) {

    public TailoringModel {
        Objects.requireNonNull(chatModel, "chatModel");
        Objects.requireNonNull(label, "label");
    }

    /** A local model whose context window is managed elsewhere (test fakes). */
    public TailoringModel(ChatModel chatModel, String label) {
        this(chatModel, label, null, true);
    }

    /** A hosted model (the profile is sent to the provider); its context window is the provider's. */
    public static TailoringModel remote(ChatModel chatModel, String label) {
        return new TailoringModel(chatModel, label, null, false);
    }
}
