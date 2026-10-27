package com.talentmatch.application.tailoring;

import static com.talentmatch.application.tailoring.DraftJson.object;
import static com.talentmatch.application.tailoring.DraftJson.stringArray;

import com.fasterxml.jackson.core.JsonProcessingException;
import dev.langchain4j.model.chat.request.ResponseFormat;
import dev.langchain4j.model.chat.request.ResponseFormatType;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonSchema;

/**
 * The LLM response schema for a cover-letter body (§4.4): {@code {"paragraphs": ["…", …]}}, hand-built
 * like {@code ProfileJsonSchema} (required, {@code additionalProperties: false}). The 1..6 paragraphs of
 * 1..1500 characters are enforced by {@link CoverLetterAssembler}, not by the schema, so a slightly
 * different answer is repaired rather than refused by a provider's grammar.
 *
 * <p>Must stay in step with {@link CoverLetterDraft}.
 */
public final class CoverLetterJsonSchema {

    public static final String NAME = "CoverLetterDraft";

    private static final ResponseFormat FORMAT = ResponseFormat.builder()
            .type(ResponseFormatType.JSON)
            .jsonSchema(JsonSchema.builder().name(NAME).rootElement(root()).build())
            .build();

    private CoverLetterJsonSchema() {
    }

    /** JSON response format carrying the schema (provider-agnostic). */
    public static ResponseFormat responseFormat() {
        return FORMAT;
    }

    /**
     * Parses the model's answer (a Markdown code fence is tolerated). Null for the JSON literal null.
     *
     * @throws JsonProcessingException malformed JSON or the wrong shape; never log its message
     */
    public static CoverLetterDraft parse(String text) throws JsonProcessingException {
        return DraftJson.read(text, CoverLetterDraft.class);
    }

    private static JsonObjectSchema root() {
        return object(null,
                "paragraphs", stringArray("The letter body: 3 or 4 paragraphs of plain text, no greeting or "
                        + "sign-off"));
    }
}
