package com.talentmatch.profile;

import dev.langchain4j.model.chat.request.ResponseFormat;
import dev.langchain4j.model.chat.request.ResponseFormatType;
import dev.langchain4j.model.chat.request.json.JsonAnyOfSchema;
import dev.langchain4j.model.chat.request.json.JsonArraySchema;
import dev.langchain4j.model.chat.request.json.JsonBooleanSchema;
import dev.langchain4j.model.chat.request.json.JsonIntegerSchema;
import dev.langchain4j.model.chat.request.json.JsonNullSchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonSchema;
import dev.langchain4j.model.chat.request.json.JsonSchemaElement;
import dev.langchain4j.model.chat.request.json.JsonStringSchema;
import java.util.List;

/**
 * The LLM response schema for CV extraction, built by hand instead of from the
 * {@link ProfileDocument} record. LangChain4j's record-derived schema marks every field required
 * and non-nullable; Ollama turns the schema into a grammar, so the model could not answer "not
 * stated" and would invent years or write "N/A". Here every optional scalar is
 * {@code anyOf: [<type>, null]}, lists may be empty, and every property is listed as required so
 * the shape is stable (strict providers need that) while null stays a legal value.
 *
 * <p>Must stay in step with {@link ProfileDocument} (field names and nesting).
 */
public final class ProfileJsonSchema {

    public static final String NAME = "ProfileDocument";

    private static final ResponseFormat FORMAT = ResponseFormat.builder()
            .type(ResponseFormatType.JSON)
            .jsonSchema(JsonSchema.builder().name(NAME).rootElement(root()).build())
            .build();

    private ProfileJsonSchema() {
    }

    /** JSON response format carrying the schema (provider-agnostic). */
    public static ResponseFormat responseFormat() {
        return FORMAT;
    }

    private static JsonObjectSchema root() {
        return object(null,
                "fullName", nullableString("Full name exactly as written in the CV, or null"),
                "email", nullableString("Email address from the CV, or null"),
                "phone", nullableString("Phone number from the CV, or null"),
                "location", nullableString("City/country from the CV, or null"),
                "headline", nullableString("The CV's own one-line title or headline, or null"),
                "summary", nullableString("The CV's profile/summary paragraph, copied faithfully, or null"),
                "links", array("Links listed in the CV (LinkedIn, GitHub, portfolio); empty if none", object(null,
                        "label", nullableString("e.g. LinkedIn, GitHub, Portfolio, or null"),
                        "url", string("The URL exactly as written"))),
                "experience", array("Every job/role in the CV, most recent first; empty if none", object(null,
                        "title", nullableString("Job title as written, or null"),
                        "company", nullableString("Employer name as written, or null"),
                        "location", nullableString("Location, or null"),
                        "startDate", nullableString("Start date as YYYY-MM or YYYY, or null"),
                        "endDate", nullableString("End date as YYYY-MM or YYYY; null if current or not stated"),
                        "current", nullableBoolean("true only if the CV says this role is current (e.g. 'Present')"),
                        "technologies", stringArray("Technologies/tools the CV names for this role; empty if none"),
                        "highlights", stringArray("Responsibilities and achievements, one bullet each, faithfully copied"))),
                "projects", array("Every project in the CV; empty if none", object(null,
                        "name", string("Project name as written"),
                        "description", nullableString("Short description from the CV, or null"),
                        "technologies", stringArray("Technologies the CV names for this project; empty if none"),
                        "url", nullableString("Project URL, or null"),
                        "highlights", stringArray("Achievements or details, one bullet each"))),
                "skills", array("Every skill, tool, language or technology named in the CV", object(null,
                        "name", string("Skill name as written, e.g. Java, PostgreSQL, Docker"),
                        "years", nullableInteger("Years of experience ONLY if the CV states a number for this "
                                + "skill, else null"))),
                "certifications", array("Every certification in the CV; empty if none", object(null,
                        "name", string("Certification name as written"),
                        "issuer", nullableString("Issuing organisation, or null"),
                        "issued", nullableString("Issue date as YYYY-MM or YYYY, or null"),
                        "expires", nullableString("Expiry date as YYYY-MM or YYYY, or null"),
                        "credentialId", nullableString("Credential ID, or null"),
                        "url", nullableString("Verification URL, or null"))),
                "education", array("Every education entry in the CV; empty if none", object(null,
                        "institution", string("School, college or university as written"),
                        "qualification", nullableString("Degree/diploma/certificate name, or null"),
                        "field", nullableString("Field of study, or null"),
                        "startDate", nullableString("Start date as YYYY-MM or YYYY, or null"),
                        "endDate", nullableString("End/graduation date as YYYY-MM or YYYY, or null"))),
                "languages", array("Spoken languages listed in the CV; empty if none", object(null,
                        "name", string("Language name"),
                        "level", nullableString("Proficiency as written, or null"))));
    }

    /** Object with the given (name, schema) pairs; all listed as required, none extra allowed. */
    private static JsonObjectSchema object(String description, Object... namesAndSchemas) {
        JsonObjectSchema.Builder b = JsonObjectSchema.builder().description(description);
        String[] names = new String[namesAndSchemas.length / 2];
        for (int i = 0; i < namesAndSchemas.length; i += 2) {
            names[i / 2] = (String) namesAndSchemas[i];
            b.addProperty(names[i / 2], (JsonSchemaElement) namesAndSchemas[i + 1]);
        }
        return b.required(names).additionalProperties(false).build();
    }

    private static JsonArraySchema array(String description, JsonSchemaElement items) {
        return JsonArraySchema.builder().description(description).items(items).build();
    }

    private static JsonArraySchema stringArray(String description) {
        return array(description, JsonStringSchema.builder().build());
    }

    private static JsonStringSchema string(String description) {
        return JsonStringSchema.builder().description(description).build();
    }

    private static JsonAnyOfSchema nullableString(String description) {
        return nullable(description, JsonStringSchema.builder().build());
    }

    private static JsonAnyOfSchema nullableInteger(String description) {
        return nullable(description, JsonIntegerSchema.builder().build());
    }

    private static JsonAnyOfSchema nullableBoolean(String description) {
        return nullable(description, JsonBooleanSchema.builder().build());
    }

    private static JsonAnyOfSchema nullable(String description, JsonSchemaElement type) {
        return JsonAnyOfSchema.builder().description(description)
                .anyOf(List.of(type, new JsonNullSchema())).build();
    }
}
