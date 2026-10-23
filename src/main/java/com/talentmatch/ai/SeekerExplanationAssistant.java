package com.talentmatch.ai;

import dev.langchain4j.service.Result;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;

/**
 * LangChain4j AI service for seeker-facing explanations (Phase 6): one stateless call per match
 * (no memory, no tools, thread-safe). Shares the {@code ChatModel} bean with
 * {@link ExplanationAssistant}; only the system prompt differs. The whole user message is built in
 * Java ({@link ExplanationPromptBuilder#buildSeeker}); no template ever contains untrusted text.
 */
public interface SeekerExplanationAssistant {

    @SystemMessage(SeekerExplanationPrompts.SYSTEM)
    Result<MatchExplanation> explain(@UserMessage String matchPrompt);
}
