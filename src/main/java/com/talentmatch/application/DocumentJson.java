package com.talentmatch.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.talentmatch.application.content.CoverLetterContent;
import com.talentmatch.application.content.DocumentKind;
import com.talentmatch.application.content.Finding;
import com.talentmatch.application.content.TailoredCvContent;
import java.util.List;

/**
 * Stored JSON form of document versions ({@code application_document.content}, {@code findings}). Uses its
 * own mapper so the stored format doesn't depend on the web layer's Jackson settings; dates are ISO strings
 * ({@code 2026-10-19}). Exceptions never carry the JSON text into logs: callers log class names only.
 */
final class DocumentJson {

    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .build();
    private static final TypeReference<List<Finding>> FINDINGS = new TypeReference<>() {
    };

    private DocumentJson() {
    }

    /** A {@link TailoredCvContent}, a {@link CoverLetterContent} or a findings list as JSON. */
    static String write(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialize document JSON", e);
        }
    }

    /** Stored content of one kind: a {@link TailoredCvContent} or a {@link CoverLetterContent}. */
    static Object content(DocumentKind kind, String json) {
        try {
            return switch (kind) {
                case CV -> MAPPER.readValue(json, TailoredCvContent.class);
                case COVER_LETTER -> MAPPER.readValue(json, CoverLetterContent.class);
            };
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Stored document JSON could not be read", e);
        }
    }

    static List<Finding> findings(String json) {
        if (json == null) {
            return List.of();
        }
        try {
            return List.copyOf(MAPPER.readValue(json, FINDINGS));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Stored findings JSON could not be read", e);
        }
    }
}
