package com.talentmatch.profile;

import static org.assertj.core.api.Assertions.assertThat;

import dev.langchain4j.model.chat.request.json.JsonAnyOfSchema;
import dev.langchain4j.model.chat.request.json.JsonArraySchema;
import dev.langchain4j.model.chat.request.json.JsonNullSchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonSchemaElement;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Phase 4 (B3): the hand-built LLM schema stays in step with ProfileDocument and lets optional fields be null. */
class ProfileJsonSchemaTest {

    /** Fields the model must always fill when an entry exists (an entry without them is dropped anyway). */
    private static final Set<String> NON_NULL = Set.of("links.url", "projects.name", "skills.name",
            "certifications.name", "education.institution", "languages.name");

    private static JsonObjectSchema root() {
        return (JsonObjectSchema) ProfileJsonSchema.responseFormat().jsonSchema().rootElement();
    }

    @Test
    void schemaPropertiesMatchTheRecordComponentsRecursively() {
        List<String> problems = new ArrayList<>();
        compare("", ProfileDocument.class, root(), problems);
        assertThat(problems).isEmpty();
    }

    @Test
    void everyScalarExceptEntryNamesIsNullable() {
        List<String> notNullable = new ArrayList<>();
        nullability("", root(), notNullable);
        assertThat(notNullable).containsExactlyInAnyOrderElementsOf(NON_NULL);
    }

    @Test
    void everyObjectListsAllPropertiesAsRequiredAndForbidsExtras() {
        List<String> problems = new ArrayList<>();
        objects("", root(), problems);
        assertThat(problems).isEmpty();
    }

    private static void compare(String path, Class<?> record, JsonObjectSchema schema, List<String> problems) {
        List<String> components = Arrays.stream(record.getRecordComponents()).map(RecordComponent::getName).toList();
        if (!components.equals(new ArrayList<>(schema.properties().keySet()))) {
            problems.add(path + ": record " + components + " vs schema " + schema.properties().keySet());
        }
        for (RecordComponent c : record.getRecordComponents()) {
            JsonSchemaElement e = unwrap(schema.properties().get(c.getName()));
            if (e instanceof JsonArraySchema a && unwrap(a.items()) instanceof JsonObjectSchema item) {
                Type t = ((ParameterizedType) c.getGenericType()).getActualTypeArguments()[0];
                compare(path + c.getName() + ".", (Class<?>) t, item, problems);
            }
        }
    }

    private static void nullability(String path, JsonObjectSchema schema, List<String> notNullable) {
        schema.properties().forEach((name, element) -> {
            if (element instanceof JsonArraySchema a) {
                if (a.items() instanceof JsonObjectSchema item) {
                    nullability(path + name + ".", item, notNullable);
                }
            } else if (!(element instanceof JsonAnyOfSchema any
                    && any.anyOf().stream().anyMatch(JsonNullSchema.class::isInstance))) {
                notNullable.add(path + name);
            }
        });
    }

    private static void objects(String path, JsonObjectSchema schema, List<String> problems) {
        if (schema.required() == null || !schema.required().containsAll(schema.properties().keySet())) {
            problems.add(path + " required=" + schema.required());
        }
        if (!Boolean.FALSE.equals(schema.additionalProperties())) {
            problems.add(path + " additionalProperties=" + schema.additionalProperties());
        }
        schema.properties().forEach((name, element) -> {
            if (element instanceof JsonArraySchema a && a.items() instanceof JsonObjectSchema item) {
                objects(path + name + ".", item, problems);
            }
        });
    }

    private static JsonSchemaElement unwrap(JsonSchemaElement e) {
        if (e instanceof JsonAnyOfSchema any) {
            return any.anyOf().stream().filter(x -> !(x instanceof JsonNullSchema)).findFirst().orElse(e);
        }
        return e;
    }
}
