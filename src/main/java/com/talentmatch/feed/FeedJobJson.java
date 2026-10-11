package com.talentmatch.feed;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.talentmatch.feed.skills.SkillRequirement;
import com.talentmatch.feed.skills.SkillSuggestion;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The jsonb arrays of {@code feed_job} (pure; own mapper, so the stored format doesn't depend on the
 * web layer's settings): dictionary_skills / ai_skills ({@link SkillRequirement}), ai_suggestions
 * ({@link SkillSuggestion}) and filter_reasons / filter_flags (enum names). Reading is lenient: a
 * malformed value reads as empty (or null for ai_skills), so one bad row can't stop the feed.
 */
public final class FeedJobJson {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private static final TypeReference<List<SkillRequirement>> SKILLS = new TypeReference<>() {
    };
    private static final TypeReference<List<SkillSuggestion>> SUGGESTIONS = new TypeReference<>() {
    };
    private static final TypeReference<List<String>> STRINGS = new TypeReference<>() {
    };

    private FeedJobJson() {
    }

    /** Skills from a jsonb array; null JSON (not enriched) → null; malformed → empty. */
    public static List<SkillRequirement> skills(String json) {
        if (json == null) {
            return null;
        }
        return clean(read(json, SKILLS));
    }

    public static List<SkillSuggestion> suggestions(String json) {
        return clean(json == null ? null : read(json, SUGGESTIONS));
    }

    public static List<String> strings(String json) {
        return clean(json == null ? null : read(json, STRINGS));
    }

    public static String write(List<?> values) {
        try {
            return MAPPER.writeValueAsString(values == null ? List.of() : values);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialize a feed_job JSON array", e);
        }
    }

    /** Enum constants as their names. */
    public static String writeNames(List<? extends Enum<?>> values) {
        List<String> names = new ArrayList<>();
        if (values != null) {
            values.stream().filter(Objects::nonNull).forEach(v -> names.add(v.name()));
        }
        return write(names);
    }

    private static <T> List<T> read(String json, TypeReference<List<T>> type) {
        try {
            return MAPPER.readValue(json, type);
        } catch (JsonProcessingException | RuntimeException e) {
            return List.of();
        }
    }

    private static <T> List<T> clean(List<T> list) {
        if (list == null) {
            return List.of();
        }
        List<T> out = new ArrayList<>(list.size());
        list.stream().filter(Objects::nonNull).forEach(out::add);
        return List.copyOf(out);
    }
}
