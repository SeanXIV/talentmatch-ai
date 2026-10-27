package com.talentmatch.application.tailoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.talentmatch.application.tailoring.TailoredCvDraft.Entry;
import com.talentmatch.application.tailoring.TailoredCvDraft.Highlight;
import dev.langchain4j.model.chat.request.ResponseFormat;
import dev.langchain4j.model.chat.request.ResponseFormatType;
import dev.langchain4j.model.chat.request.json.JsonAnyOfSchema;
import dev.langchain4j.model.chat.request.json.JsonArraySchema;
import dev.langchain4j.model.chat.request.json.JsonNullSchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonSchemaElement;
import dev.langchain4j.model.chat.request.json.JsonStringSchema;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.ParameterizedType;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** §9.1.4: every property required, nullable scalars as anyOf, additionalProperties false, parse round trip. */
class TailoringJsonSchemaTest {

    private static JsonObjectSchema root(ResponseFormat format) {
        return (JsonObjectSchema) format.jsonSchema().rootElement();
    }

    /** Every object: all properties required, no additional properties. Collects paths of nullable properties. */
    private static void walk(String path, JsonObjectSchema schema, List<String> problems, List<String> nullable) {
        if (schema.required() == null || !schema.required().containsAll(schema.properties().keySet())) {
            problems.add(path + " required=" + schema.required());
        }
        if (!Boolean.FALSE.equals(schema.additionalProperties())) {
            problems.add(path + " additionalProperties=" + schema.additionalProperties());
        }
        schema.properties().forEach((name, element) -> {
            String p = path + name;
            if (element instanceof JsonAnyOfSchema any) {
                if (any.anyOf().stream().anyMatch(JsonNullSchema.class::isInstance)
                        && any.anyOf().stream().anyMatch(JsonStringSchema.class::isInstance)) {
                    nullable.add(p);
                } else {
                    problems.add(p + " anyOf without string|null");
                }
            } else if (element instanceof JsonArraySchema a && a.items() instanceof JsonObjectSchema item) {
                walk(p + "[].", item, problems, nullable);
            }
        });
    }

    /** The schema's property names match the record's components at every level. */
    private static void sameShape(String path, Class<?> record, JsonObjectSchema schema, List<String> problems) {
        List<String> components = new ArrayList<>();
        for (RecordComponent c : record.getRecordComponents()) {
            components.add(c.getName());
            JsonSchemaElement e = schema.properties().get(c.getName());
            if (e instanceof JsonArraySchema a && a.items() instanceof JsonObjectSchema item
                    && c.getGenericType() instanceof ParameterizedType t) {
                sameShape(path + c.getName() + "[].", (Class<?>) t.getActualTypeArguments()[0], item, problems);
            }
        }
        if (!List.copyOf(schema.properties().keySet()).equals(components)) {
            problems.add(path + " schema=" + schema.properties().keySet() + " record=" + components);
        }
    }

    @Test
    void theCvSchemaIsStrictAndOnlyHeadlineAndSummaryAreNullable() {
        ResponseFormat f = TailoredCvJsonSchema.responseFormat();
        assertThat(f.type()).isEqualTo(ResponseFormatType.JSON);
        assertThat(f.jsonSchema().name()).isEqualTo(TailoredCvJsonSchema.NAME).isEqualTo("TailoredCvDraft");
        List<String> problems = new ArrayList<>();
        List<String> nullable = new ArrayList<>();
        walk("", root(f), problems, nullable);
        sameShape("", TailoredCvDraft.class, root(f), problems);
        assertThat(problems).isEmpty();
        assertThat(nullable).containsExactly("headline", "summary");
        JsonArraySchema experience = (JsonArraySchema) root(f).properties().get("experience");
        JsonObjectSchema entry = (JsonObjectSchema) experience.items();
        assertThat(entry.properties()).containsOnlyKeys("ref", "highlights");
        JsonObjectSchema highlight = (JsonObjectSchema) ((JsonArraySchema) entry.properties().get("highlights")).items();
        assertThat(highlight.properties()).containsOnlyKeys("ref", "text");
        assertThat(((JsonArraySchema) root(f).properties().get("certifications")).items())
                .isInstanceOf(JsonStringSchema.class);
    }

    @Test
    void theLetterSchemaIsStrict() {
        ResponseFormat f = CoverLetterJsonSchema.responseFormat();
        assertThat(f.jsonSchema().name()).isEqualTo("CoverLetterDraft");
        List<String> problems = new ArrayList<>();
        List<String> nullable = new ArrayList<>();
        walk("", root(f), problems, nullable);
        sameShape("", CoverLetterDraft.class, root(f), problems);
        assertThat(problems).isEmpty();
        assertThat(nullable).isEmpty();
        assertThat(root(f).properties()).containsOnlyKeys("paragraphs");
        assertThat(((JsonArraySchema) root(f).properties().get("paragraphs")).items())
                .isInstanceOf(JsonStringSchema.class);
    }

    static final String CV_JSON = """
            {"headline": null, "summary": "Backend engineer.",
             "skills": ["Java", "PostgreSQL"],
             "experience": [{"ref": "E1", "highlights": [{"ref": "E1.H2", "text": "Led five engineers"}]}],
             "projects": [{"ref": "P1", "highlights": []}],
             "certifications": ["C1"]}""";

    @Test
    void aSampleCvAnswerParses() throws JsonProcessingException {
        TailoredCvDraft expected = new TailoredCvDraft(null, "Backend engineer.", List.of("Java", "PostgreSQL"),
                List.of(new Entry("E1", List.of(new Highlight("E1.H2", "Led five engineers")))),
                List.of(new Entry("P1", List.of())), List.of("C1"));
        assertThat(TailoredCvJsonSchema.parse(CV_JSON)).isEqualTo(expected);
        assertThat(TailoredCvJsonSchema.parse("```json\n" + CV_JSON + "\n```")).as("code fence").isEqualTo(expected);
        assertThat(TailoredCvJsonSchema.parse("  ```\n" + CV_JSON + "\n```  ")).isEqualTo(expected);
        assertThat(TailoredCvJsonSchema.parse(CV_JSON.replace("\"certifications\"", "\"extra\": 1, \"certifications\"")))
                .as("unknown properties are ignored").isEqualTo(expected);
        assertThat(TailoredCvJsonSchema.parse("{}")).isEqualTo(new TailoredCvDraft(null, null, null, null, null, null));
        assertThat(TailoredCvJsonSchema.parse("null")).isNull();
    }

    @Test
    void aSampleLetterAnswerParses() throws JsonProcessingException {
        assertThat(CoverLetterJsonSchema.parse("{\"paragraphs\": [\"One.\", \"Two.\"]}"))
                .isEqualTo(new CoverLetterDraft(List.of("One.", "Two.")));
        assertThat(CoverLetterJsonSchema.parse("```\n{\"paragraphs\": []}\n```")).isEqualTo(new CoverLetterDraft(List.of()));
        assertThat(CoverLetterJsonSchema.parse("null")).isNull();
    }

    @Test
    void malformedAnswersThrow() {
        for (String bad : new String[] {"", "   ", "not json", "{\"paragraphs\": [", "```json\n{\"a\":", "```",
                "{\"paragraphs\": \"one\"}x"}) {
            assertThatThrownBy(() -> CoverLetterJsonSchema.parse(bad)).as("%s", bad)
                    .isInstanceOf(JsonProcessingException.class);
        }
        assertThatThrownBy(() -> TailoredCvJsonSchema.parse("{\"experience\": 5}"))
                .isInstanceOf(JsonProcessingException.class);
        assertThatThrownBy(() -> TailoredCvJsonSchema.parse(null)).isInstanceOf(JsonProcessingException.class);
    }
}
