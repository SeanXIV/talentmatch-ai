package com.talentmatch.profile;

import dev.langchain4j.model.chat.ChatModel;
import java.util.Objects;

/**
 * The provider's model built for CV extraction (long timeout, large output and context). A holder
 * type, so the explanation {@link ChatModel} stays the only ChatModel bean.
 *
 * @param chatModel     the configured model
 * @param label         {@code provider/model}, stored with each draft
 * @param contextTokens the model's context window when we set it ourselves (Ollama {@code num_ctx});
 *                      null when the provider manages it. Used to detect a prompt that was cut off.
 * @param local         true when the model runs on this machine (Ollama): the CV never leaves it.
 *                      A remote model (OpenAI, Claude) is only called with
 *                      {@code talentmatch.profile.allow-remote-extraction=true}.
 */
public record ResumeExtractionModel(ChatModel chatModel, String label, Integer contextTokens, boolean local) {

    public ResumeExtractionModel {
        Objects.requireNonNull(chatModel, "chatModel");
        Objects.requireNonNull(label, "label");
    }

    /** A local model whose context window is managed elsewhere (test fakes). */
    public ResumeExtractionModel(ChatModel chatModel, String label) {
        this(chatModel, label, null, true);
    }

    /** A local model with a known context window. */
    public ResumeExtractionModel(ChatModel chatModel, String label, Integer contextTokens) {
        this(chatModel, label, contextTokens, true);
    }

    /** A hosted model (the CV is sent to the provider); its context window is the provider's. */
    public static ResumeExtractionModel remote(ChatModel chatModel, String label) {
        return new ResumeExtractionModel(chatModel, label, null, false);
    }
}
