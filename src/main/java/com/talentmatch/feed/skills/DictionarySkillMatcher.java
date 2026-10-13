package com.talentmatch.feed.skills;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds known skills (names and aliases) in posting text (pure, never invents a skill).
 * <ul>
 *   <li>Token boundaries {@code (?<![\p{L}\p{N}+#.])} and {@code (?![\p{L}\p{N}+#])}: "C++", "C#",
 *       ".NET" and "Node.js" match, and "Java" doesn't match inside "JavaScript".</li>
 *   <li>Terms of 3 characters or fewer ("Go", "R", "SQL", "AWS") match case-sensitively; longer
 *       ones ignore case.</li>
 *   <li>Ambiguous names ("Go", "Swift", "Rust", …) count only in a sentence that also mentions a
 *       non-ambiguous skill or a tech context word (language, framework, experience with, stack,
 *       developer, engineer), so "Go-getter" or "swift delivery" don't count.</li>
 *   <li>Each skill is reported once, at its first counted mention; {@link RequirementHeuristic}
 *       decides required vs nice-to-have from that mention.</li>
 * </ul>
 * Immutable and thread-safe.
 */
public final class DictionarySkillMatcher {

    public static final List<String> DEFAULT_AMBIGUOUS_NAMES =
            List.of("Go", "Swift", "Rust", "Spark", "Chef", "Puppet", "Ruby");

    /** Terms this long or shorter match case-sensitively. */
    public static final int CASE_SENSITIVE_MAX_LENGTH = 3;

    private static final String BEFORE = "(?<![\\p{L}\\p{N}+#.])";
    private static final String AFTER = "(?![\\p{L}\\p{N}+#])";
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final Pattern TECH_CONTEXT = Pattern.compile(
            "(?iu)\\b(?:languages?|frameworks?|experience with|stacks?|developers?|engineers?)\\b");

    /** One searchable name of a skill: its own name or an alias. */
    public record Term(UUID skillId, String skillName, String text) {
    }

    private record CompiledTerm(Term term, Pattern pattern, String lowerText, boolean ambiguous) {
    }

    private record Occurrence(UUID skillId, String skillName, int start, boolean ambiguous) {
    }

    private final List<CompiledTerm> terms;
    /** Each skill's name and alias patterns (AI grounding). */
    private final Map<UUID, List<Pattern>> patternsBySkill;

    public DictionarySkillMatcher(Collection<Term> terms, Collection<String> ambiguousNames) {
        Set<String> ambiguous = new HashSet<>();
        if (ambiguousNames != null) {
            ambiguousNames.forEach(n -> {
                if (n != null && !n.isBlank()) {
                    ambiguous.add(n.strip().toLowerCase(Locale.ROOT));
                }
            });
        }
        List<CompiledTerm> compiled = new ArrayList<>();
        for (Term t : terms) {
            if (t == null || t.skillId() == null || t.text() == null || t.text().isBlank()) {
                continue;
            }
            String text = t.text().strip();
            int flags = text.length() <= CASE_SENSITIVE_MAX_LENGTH ? 0 : Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
            boolean isAmbiguous = ambiguous.contains(text.toLowerCase(Locale.ROOT))
                    || (t.skillName() != null && ambiguous.contains(t.skillName().toLowerCase(Locale.ROOT)));
            compiled.add(new CompiledTerm(t, Pattern.compile(BEFORE + Pattern.quote(text) + AFTER, flags),
                    text.toLowerCase(Locale.ROOT), isAmbiguous));
        }
        this.terms = List.copyOf(compiled);
        Map<UUID, List<Pattern>> bySkill = new HashMap<>();
        for (CompiledTerm ct : compiled) {
            bySkill.computeIfAbsent(ct.term().skillId(), id -> new ArrayList<>()).add(ct.pattern());
        }
        bySkill.replaceAll((id, patterns) -> List.copyOf(patterns));
        this.patternsBySkill = Map.copyOf(bySkill);
    }

    /** Matches in the title and description together (title first, as its own paragraph). */
    public List<SkillMention> match(String title, String description) {
        String t = title == null ? "" : title;
        String d = description == null ? "" : description;
        return match(d.isEmpty() ? t : t + "\n\n" + d);
    }

    /** Skills found in the text, ordered by first counted mention (then name). */
    public List<SkillMention> match(String text) {
        if (text == null || text.isBlank() || terms.isEmpty()) {
            return List.of();
        }
        String lower = text.toLowerCase(Locale.ROOT);
        List<Occurrence> found = new ArrayList<>();
        for (CompiledTerm ct : terms) {
            if (!lower.contains(ct.lowerText())) {      // cheap pre-filter; the regex decides
                continue;
            }
            Matcher m = ct.pattern().matcher(text);
            while (m.find()) {
                found.add(new Occurrence(ct.term().skillId(), ct.term().skillName(), m.start(), ct.ambiguous()));
            }
        }
        if (found.isEmpty()) {
            return List.of();
        }
        found.sort(Comparator.comparingInt(Occurrence::start));

        Map<UUID, Occurrence> first = new LinkedHashMap<>();
        for (Occurrence o : found) {
            if (first.containsKey(o.skillId())) {
                continue;
            }
            if (!o.ambiguous() || confirmed(text, o, found)) {
                first.put(o.skillId(), o);
            }
        }
        List<SkillMention> out = new ArrayList<>(first.size());
        for (Occurrence o : first.values()) {
            out.add(new SkillMention(o.skillId(), o.skillName(), !RequirementHeuristic.niceToHave(text, o.start()),
                    o.start()));
        }
        out.sort(Comparator.comparingInt(SkillMention::offset)
                .thenComparing(m -> m.name() == null ? "" : m.name().toLowerCase(Locale.ROOT)));
        return List.copyOf(out);
    }

    /** An ambiguous mention counts if its sentence has a tech context word or a non-ambiguous skill. */
    private static boolean confirmed(String text, Occurrence o, List<Occurrence> all) {
        int start = RequirementHeuristic.sentenceStart(text, o.start());
        int end = RequirementHeuristic.sentenceEnd(text, o.start());
        if (TECH_CONTEXT.matcher(text.substring(start, end)).find()) {
            return true;
        }
        for (Occurrence other : all) {
            if (other.start() >= end) {
                break;                                   // sorted by start
            }
            if (other.start() >= start && !other.ambiguous() && !other.skillId().equals(o.skillId())) {
                return true;
            }
        }
        return false;
    }

    public int termCount() {
        return terms.size();
    }

    /**
     * True when {@code text} mentions {@code term} by the matcher's rules: token boundaries, and
     * case-sensitive for terms of {@value #CASE_SENSITIVE_MAX_LENGTH} characters or fewer (AI grounding,
     * §4.7). No ambiguity check: the caller already has the phrase that names the skill.
     */
    public static boolean mentions(String text, String term) {
        if (text == null || term == null || term.isBlank()) {
            return false;
        }
        String t = term.strip();
        int flags = t.length() <= CASE_SENSITIVE_MAX_LENGTH ? 0 : Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
        return Pattern.compile(BEFORE + Pattern.quote(t) + AFTER, flags).matcher(text).find();
    }

    /**
     * True when {@code text} mentions the skill by its stored name or one of its stored aliases, by the
     * matcher's boundary and case rules (AI grounding, §4.7). No ambiguity check, as {@link #mentions}.
     */
    public boolean mentionsSkill(String text, UUID skillId) {
        if (text == null || skillId == null) {
            return false;
        }
        for (Pattern p : patternsBySkill.getOrDefault(skillId, List.of())) {
            if (p.matcher(text).find()) {
                return true;
            }
        }
        return false;
    }

    /**
     * The phrase {@code evidence} as it appears in {@code text}: its words in order, separated by any
     * whitespace (line breaks included), ignoring case, starting and ending on token boundaries. Empty
     * when the text doesn't contain it.
     */
    public static Optional<String> locate(String text, String evidence) {
        if (text == null || evidence == null || evidence.isBlank()) {
            return Optional.empty();
        }
        StringBuilder regex = new StringBuilder(BEFORE);
        String[] tokens = WHITESPACE.split(evidence.strip());
        for (int i = 0; i < tokens.length; i++) {
            if (i > 0) {
                regex.append("\\s+");
            }
            regex.append(Pattern.quote(tokens[i]));
        }
        regex.append(AFTER);
        Matcher m = Pattern.compile(regex.toString(), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE).matcher(text);
        return m.find() ? Optional.of(m.group()) : Optional.empty();
    }
}
