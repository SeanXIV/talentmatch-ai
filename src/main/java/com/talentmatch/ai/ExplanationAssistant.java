package com.talentmatch.ai;

import dev.langchain4j.service.Result;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;

/**
 * LangChain4j AI service: one stateless call per match (no memory, no tools, thread-safe).
 * The whole user message is built in Java ({@link ExplanationPromptBuilder}); no template ever
 * contains untrusted text.
 */
public interface ExplanationAssistant {

    @SystemMessage(ExplanationPrompts.SYSTEM)
    Result<MatchExplanation> explain(@UserMessage String matchPrompt);
}
