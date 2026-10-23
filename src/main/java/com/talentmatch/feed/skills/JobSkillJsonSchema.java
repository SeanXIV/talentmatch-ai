package com.talentmatch.feed.skills;

import dev.langchain4j.model.chat.request.ResponseFormat;
import dev.langchain4j.model.chat.request.ResponseFormatType;
import dev.langchain4j.model.chat.request.json.JsonArraySchema;
import dev.langchain4j.model.chat.request.json.JsonEnumSchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonSchema;
import dev.langchain4j.model.chat.request.json.JsonStringSchema;

/**
 * The response schema for job-skill enrichment (§4.7), built by hand as {@code ProfileJsonSchema} is:
 * {@code {"skills":[{"name","requirement":"REQUIRED"|"NICE_TO_HAVE","evidence"}]}}, every field
 * required. The 40-item cap is enforced by {@link JobSkillValidator} (not every provider accepts
 * {@code maxItems}).
 */
public final class JobSkillJsonSchema {

    public static final String NAME = "JobSkills";

    private static final ResponseFormat FORMAT = ResponseFormat.builder()
            .type(ResponseFormatType.JSON)
            .jsonSchema(JsonSchema.builder().name(NAME).rootElement(JsonObjectSchema.builder()
                    .addProperty("skills", JsonArraySchema.builder()
                            .description("Skills the posting asks for, each once; at most 40")
                            .items(JsonObjectSchema.builder()
                                    .addProperty("name", JsonStringSchema.builder()
                                            .description("The skill as the posting writes it").build())
                                    .addProperty("requirement", JsonEnumSchema.builder()
                                            .enumValues("REQUIRED", "NICE_TO_HAVE").build())
                                    .addProperty("evidence", JsonStringSchema.builder()
                                            .description("The shortest phrase copied exactly from the posting "
                                                    + "that names the skill").build())
                                    .required("name", "requirement", "evidence")
                                    .additionalProperties(false)
                                    .build())
                            .build())
                    .required("skills")
                    .additionalProperties(false)
                    .build()).build())
            .build();

    private JobSkillJsonSchema() {
    }

    public static ResponseFormat responseFormat() {
        return FORMAT;
    }
}
