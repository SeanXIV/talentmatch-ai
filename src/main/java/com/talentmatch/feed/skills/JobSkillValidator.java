package com.talentmatch.feed.skills;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.talentmatch.service.TextNormalizer;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Checks the model's answer against the posting (§4.7; pure). Nothing the posting doesn't say gets
 * through, and nothing new is ever created:
 * <ol>
 *   <li>The name is normalized ({@link TextNormalizer#skillName}); blank or over 100 characters → dropped.</li>
 *   <li><b>Grounding:</b> the evidence must appear in the posting text (on token boundaries, any
 *       whitespace, case ignored), and that span of the posting must name the skill by the dictionary's
 *       boundary and case rules: a vocabulary skill by its stored name or alias, any other name as
 *       written. Otherwise the item is dropped as ungrounded.</li>
 *   <li>A name that resolves to a skill or alias is <b>accepted</b> (the skill's own name, the
 *       requirement); anything else grounded is a <b>suggestion</b> for the owner (at most
 *       {@value #MAX_SUGGESTIONS}).</li>
 * </ol>
 * The whole answer is rejected (INVALID_OUTPUT) when it is not the expected JSON, or when any field
 * echoes the prompt ({@code <job_posting}, {@code MATCH FACTS}). Only the first {@value #MAX_ITEMS}
 * items are read; each skill and suggestion is kept once (first occurrence wins).
 */
public final class JobSkillValidator {

    public static final int MAX_ITEMS = 40;
    public static final int MAX_SUGGESTIONS = 20;
    static final int MAX_NAME_LENGTH = 100;

    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .disable(StreamReadFeature.INCLUDE_SOURCE_IN_LOCATION)        // no answer text in messages
            .build();
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final List<String> ECHOES = List.of("<job_posting", "</job_posting", "match facts");

    private JobSkillValidator() {
    }

    /**
     * @param accepted    vocabulary skills, in answer order
     * @param suggestions grounded names outside the vocabulary
     * @param ungrounded  items dropped because the evidence or name isn't in the posting
     * @param dropped     items dropped for other reasons (blank or overlong name, duplicate, over the caps)
     */
    public record Result(List<SkillRequirement> accepted, List<SkillSuggestion> suggestions, int ungrounded,
                         int dropped) {

        public Result {
            accepted = List.copyOf(accepted);
            suggestions = List.copyOf(suggestions);
        }
    }

    /** The answer can't be used at all. */
    public static final class InvalidOutputException extends Exception {

        private static final long serialVersionUID = 1L;

        InvalidOutputException(String message) {
            super(message, null, false, false);
        }
    }

    /**
     * @param answer      the model's raw text
     * @param postingText the text the model was given ({@link JobSkillPrompts#postingText})
     * @param vocabulary  the current skill vocabulary
     * @throws InvalidOutputException when the answer is not usable (the message holds no answer text)
     */
    public static Result validate(String answer, String postingText, SkillDictionary.Snapshot vocabulary)
            throws InvalidOutputException {
        JsonNode items = parse(answer);
        Map<UUID, SkillRequirement> accepted = new LinkedHashMap<>();
        Map<String, SkillSuggestion> suggestions = new LinkedHashMap<>();
        Set<String> seen = new HashSet<>();
        int ungrounded = 0;
        int dropped = 0;
        int index = 0;
        for (JsonNode item : items) {
            if (index++ >= MAX_ITEMS) {
                dropped++;
                continue;
            }
            String name = TextNormalizer.skillName(text(item, "name"));
            String evidence = text(item, "evidence");
            boolean required = !"NICE_TO_HAVE".equalsIgnoreCase(text(item, "requirement"));
            if (name == null || name.length() > MAX_NAME_LENGTH) {
                dropped++;
                continue;
            }
            Optional<UUID> skillId = vocabulary.resolve(name);
            String canonical = skillId.flatMap(vocabulary::name).orElse(null);
            if (!grounded(postingText, evidence, name, skillId.orElse(null), canonical, vocabulary)) {
                ungrounded++;
                continue;
            }
            if (skillId.isPresent()) {
                if (accepted.putIfAbsent(skillId.get(), new SkillRequirement(skillId.get(), canonical, required)) != null) {
                    dropped++;
                }
                continue;
            }
            String key = TextNormalizer.skillKey(name);
            if (!seen.add(key) || suggestions.size() >= MAX_SUGGESTIONS) {
                dropped++;
                continue;
            }
            suggestions.put(key, new SkillSuggestion(name, required ? "REQUIRED" : "NICE_TO_HAVE",
                    WHITESPACE.matcher(evidence.strip()).replaceAll(" ")));
        }
        return new Result(new ArrayList<>(accepted.values()), new ArrayList<>(suggestions.values()), ungrounded,
                dropped);
    }

    /**
     * The evidence is located in the posting itself ({@link DictionarySkillMatcher#locate}: token boundaries,
     * any whitespace between words, case ignored), and the skill must be named in that span of the posting,
     * never just in the model's copy of it. A vocabulary skill must appear by its stored name or a stored
     * alias under the matcher's rules (so "go" in "ready to go" is not Go, and "rust" in "trust" is nothing).
     * Two direct checks back the matcher up for a snapshot whose matcher lacks the terms: the stored name,
     * and a resolved name longer than {@value DictionarySkillMatcher#CASE_SENSITIVE_MAX_LENGTH} characters
     * (it equals a stored name or alias ignoring case, which is the matcher's rule for such terms; a short
     * one is never trusted in the model's casing). A name outside the vocabulary must itself appear in the span.
     */
    private static boolean grounded(String postingText, String evidence, String name, UUID skillId, String canonical,
                                    SkillDictionary.Snapshot vocabulary) {
        Optional<String> span = DictionarySkillMatcher.locate(postingText, evidence);
        if (span.isEmpty()) {
            return false;
        }
        String s = span.get();
        if (skillId == null) {
            return DictionarySkillMatcher.mentions(s, name);
        }
        return vocabulary.matcher().mentionsSkill(s, skillId)
                || (canonical != null && DictionarySkillMatcher.mentions(s, canonical))
                || (name.length() > DictionarySkillMatcher.CASE_SENSITIVE_MAX_LENGTH
                        && DictionarySkillMatcher.mentions(s, name));
    }

    private static JsonNode parse(String answer) throws InvalidOutputException {
        if (answer == null || answer.isBlank()) {
            throw new InvalidOutputException("empty answer");
        }
        String lower = answer.toLowerCase(Locale.ROOT);
        for (String echo : ECHOES) {
            if (lower.contains(echo)) {
                throw new InvalidOutputException("the answer echoes the prompt");
            }
        }
        JsonNode root;
        try {
            root = MAPPER.readTree(answer);
        } catch (IOException e) {
            throw new InvalidOutputException("not JSON (" + e.getClass().getSimpleName() + ")");
        }
        JsonNode skills = root == null ? null : root.get("skills");
        if (skills == null || !skills.isArray()) {
            throw new InvalidOutputException("no skills array");
        }
        return skills;
    }

    private static String text(JsonNode item, String field) {
        JsonNode node = item == null ? null : item.get(field);
        if (node == null || !node.isTextual()) {
            return null;
        }
        String v = node.asText().strip();
        return v.isEmpty() ? null : v;
    }
}
