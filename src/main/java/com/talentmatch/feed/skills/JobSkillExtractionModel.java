package com.talentmatch.feed.skills;

import dev.langchain4j.model.chat.ChatModel;
import java.util.Objects;

/**
 * The provider's model built for job-skill enrichment (§4.7): its own timeout and output budget,
 * temperature 0. A holder type, so the explanation {@link ChatModel} stays the only ChatModel bean.
 * Postings are public and the CV is never in the prompt, so hosted providers need no opt-in
 * (decision m).
 *
 * @param label         {@code provider/model}, stored with each enrichment
 * @param contextTokens Ollama {@code num_ctx} (detects a cut-off prompt); null when the provider manages it
 * @param local         the model runs on this machine (shares {@code LocalModelGate} with CV extraction)
 */
public record JobSkillExtractionModel(ChatModel chatModel, String label, Integer contextTokens, boolean local) {

    public JobSkillExtractionModel {
        Objects.requireNonNull(chatModel, "chatModel");
        Objects.requireNonNull(label, "label");
    }

    /** A hosted model; its context window is the provider's. */
    public static JobSkillExtractionModel remote(ChatModel chatModel, String label) {
        return new JobSkillExtractionModel(chatModel, label, null, false);
    }
}
