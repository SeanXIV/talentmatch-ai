package com.talentmatch.ai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Optional;

/**
 * JSON form of a validated {@link MatchExplanation} as stored in job_match.explanation_payload:
 * {@code {"headline", "explanation", "strengths": [], "gaps": []}}. Uses its own mapper so the
 * stored format does not depend on the web layer's Jackson settings.
 */
final class ExplanationPayloadCodec {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private ExplanationPayloadCodec() {
    }

    static String toJson(MatchExplanation explanation) {
        try {
            return MAPPER.writeValueAsString(explanation);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialize an explanation payload", e);
        }
    }

    /** Empty if the JSON is absent or cannot be read. */
    static Optional<MatchExplanation> fromJson(String json) {
        if (json == null || json.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(MAPPER.readValue(json, MatchExplanation.class));
        } catch (JsonProcessingException e) {
            return Optional.empty();
        }
    }
}
