package com.talentmatch.application.tailoring;

import static com.talentmatch.application.tailoring.DraftJson.array;
import static com.talentmatch.application.tailoring.DraftJson.nullableString;
import static com.talentmatch.application.tailoring.DraftJson.object;
import static com.talentmatch.application.tailoring.DraftJson.string;
import static com.talentmatch.application.tailoring.DraftJson.stringArray;

import com.fasterxml.jackson.core.JsonProcessingException;
import dev.langchain4j.model.chat.request.ResponseFormat;
import dev.langchain4j.model.chat.request.ResponseFormatType;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonSchema;

/**
 * The LLM response schema for a tailored CV (§4.3), built by hand like {@code ProfileJsonSchema}: every
 * property required, {@code headline}/{@code summary} nullable ({@code anyOf [string, null]}; null keeps
 * the profile's text), lists may be empty, {@code additionalProperties: false} at every level.
 *
 * <p>Must stay in step with {@link TailoredCvDraft} (field names and nesting).
 */
public final class TailoredCvJsonSchema {

    public static final String NAME = "TailoredCvDraft";

    private static final ResponseFormat FORMAT = ResponseFormat.builder()
            .type(ResponseFormatType.JSON)
            .jsonSchema(JsonSchema.builder().name(NAME).rootElement(root()).build())
            .build();

    private TailoredCvJsonSchema() {
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
    public static TailoredCvDraft parse(String text) throws JsonProcessingException {
        return DraftJson.read(text, TailoredCvDraft.class);
    }

    private static JsonObjectSchema root() {
        return object(null,
                "headline", nullableString("At most 12 words based on HEADLINE and the roles, or null to keep "
                        + "HEADLINE"),
                "summary", nullableString("2 to 4 sentences based only on SUMMARY, the roles and SKILLS, or null "
                        + "to keep SUMMARY"),
                "skills", stringArray("Up to 20 exact names from SKILLS, most relevant to the job first"),
                "experience", array("Roles by ref (E1, E2, ...), each with its chosen highlights", entry(
                        "Role ref from PROFILE FACTS, e.g. E1",
                        "2 to 5 highlights of this role, most relevant first")),
                "projects", array("Projects by ref (P1, ...) worth showing for this job; empty if none", entry(
                        "Project ref from PROFILE FACTS, e.g. P1",
                        "Highlights of this project, most relevant first; empty if none")),
                "certifications", stringArray("Certification refs (C1, ...) worth showing for this job; empty "
                        + "if none"));
    }

    private static JsonObjectSchema entry(String refDescription, String highlightsDescription) {
        return object(null,
                "ref", string(refDescription),
                "highlights", array(highlightsDescription, object(null,
                        "ref", string("Highlight ref from PROFILE FACTS under this entry, e.g. E1.H2"),
                        "text", string("The highlight reworded for the job; same facts and numbers"))));
    }
}
