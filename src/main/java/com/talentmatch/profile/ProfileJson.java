package com.talentmatch.profile;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;

/**
 * Stored JSON form of profile documents and warnings (resume.draft, resume.warnings,
 * owner_profile.profile). Uses its own mapper so the stored format does not depend on the web
 * layer's Jackson settings.
 */
final class ProfileJson {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private static final TypeReference<List<ProfileWarning>> WARNINGS = new TypeReference<>() {
    };

    private ProfileJson() {
    }

    static String write(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialize profile JSON", e);
        }
    }

    static ProfileDocument document(String json) {
        if (json == null) {
            return null;
        }
        try {
            // Re-normalize so older stored shapes (e.g. no experience technologies) read as a complete
            // document. STRICT keeps every stored value as it is (LENIENT would e.g. turn a saved
            // "N/A" into null); its issues are ignored here.
            return ProfileNormalizer.normalize(MAPPER.readValue(json, ProfileDocument.class),
                    ProfileNormalizer.Mode.STRICT).document();
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Stored profile JSON could not be read", e);
        }
    }

    /**
     * The model's answer as a document, as-is (normalization comes next). Tolerates a Markdown
     * code fence around the JSON. Throws {@link JsonProcessingException} for anything else.
     */
    static ProfileDocument modelOutput(String text) throws JsonProcessingException {
        String t = text == null ? "" : text.strip();
        if (t.startsWith("```")) {
            int firstLine = t.indexOf('\n');
            int lastFence = t.lastIndexOf("```");
            t = firstLine < 0 || lastFence <= firstLine ? "" : t.substring(firstLine + 1, lastFence).strip();
        }
        return MAPPER.readValue(t, ProfileDocument.class);
    }

    static List<ProfileWarning> warnings(String json) {
        if (json == null) {
            return List.of();
        }
        try {
            return List.copyOf(MAPPER.readValue(json, WARNINGS));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Stored warnings JSON could not be read", e);
        }
    }
}
