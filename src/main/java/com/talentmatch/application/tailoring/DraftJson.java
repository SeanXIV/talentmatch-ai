package com.talentmatch.application.tailoring;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import dev.langchain4j.model.chat.request.json.JsonAnyOfSchema;
import dev.langchain4j.model.chat.request.json.JsonArraySchema;
import dev.langchain4j.model.chat.request.json.JsonNullSchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonSchemaElement;
import dev.langchain4j.model.chat.request.json.JsonStringSchema;
import java.util.List;

/**
 * Shared parts of the tailoring answer schemas ({@link TailoredCvJsonSchema}, {@link CoverLetterJsonSchema})
 * and the parser of the model's answers. Same conventions as {@code ProfileJsonSchema}: every property is
 * required, optional scalars are {@code anyOf: [<type>, null]}, no additional properties.
 */
final class DraftJson {

    /** Own mapper: unknown properties are ignored (the assembler polices content, not shape). */
    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .build();

    private DraftJson() {
    }

    /**
     * The model's answer as {@code type}. Tolerates a Markdown code fence around the JSON. Throws
     * {@link JsonProcessingException} for anything else (its message may quote the answer: never log it).
     * Returns null for the JSON literal {@code null}.
     */
    static <T> T read(String text, Class<T> type) throws JsonProcessingException {
        String t = text == null ? "" : text.strip();
        if (t.startsWith("```")) {
            int firstLine = t.indexOf('\n');
            int lastFence = t.lastIndexOf("```");
            t = firstLine < 0 || lastFence <= firstLine ? "" : t.substring(firstLine + 1, lastFence).strip();
        }
        return MAPPER.readValue(t, type);
    }

    /** Object with the given (name, schema) pairs; all listed as required, none extra allowed. */
    static JsonObjectSchema object(String description, Object... namesAndSchemas) {
        JsonObjectSchema.Builder b = JsonObjectSchema.builder().description(description);
        String[] names = new String[namesAndSchemas.length / 2];
        for (int i = 0; i < namesAndSchemas.length; i += 2) {
            names[i / 2] = (String) namesAndSchemas[i];
            b.addProperty(names[i / 2], (JsonSchemaElement) namesAndSchemas[i + 1]);
        }
        return b.required(names).additionalProperties(false).build();
    }

    static JsonArraySchema array(String description, JsonSchemaElement items) {
        return JsonArraySchema.builder().description(description).items(items).build();
    }

    static JsonArraySchema stringArray(String description) {
        return array(description, JsonStringSchema.builder().build());
    }

    static JsonStringSchema string(String description) {
        return JsonStringSchema.builder().description(description).build();
    }

    static JsonAnyOfSchema nullableString(String description) {
        return JsonAnyOfSchema.builder().description(description)
                .anyOf(List.of(JsonStringSchema.builder().build(), new JsonNullSchema())).build();
    }
}
