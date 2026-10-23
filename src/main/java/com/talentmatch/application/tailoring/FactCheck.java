package com.talentmatch.application.tailoring;

import com.talentmatch.application.content.FindingCode;
import com.talentmatch.feed.skills.DictionarySkillMatcher;
import com.talentmatch.feed.skills.SkillDictionary;
import com.talentmatch.feed.skills.SkillMention;
import com.talentmatch.profile.ProfileText;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The never-invent checks on tailored free text (§4.4, decision h). Pure apart from one WARN log line
 * in {@link #load}; immutable and thread-safe. Never logs text.
 *
 * <ul>
 *   <li><b>Numbers:</b> every number token ({@link ProfileText#NUMBER}, canonical by
 *       {@link ProfileText#canonicalNumber}: {@code 10,000} = {@code 10000}; {@code 40%}, {@code 40 per cent}
 *       and {@code 5+} are the numbers 40 and 5) must be allowed. No rounding or unit conversion
 *       ({@code $1.2M} is 1.2, not 1200000). Number words two..twenty count as their digits ("one" is not
 *       checked: it is mostly a pronoun). A token inside a mention of an allowed skill or alias ("HTML5",
 *       "k8s") is exempt by span. With an allowed-years set, 4-digit years 1900..2099 are checked against
 *       it instead ({@link FindingCode#YEAR_NOT_IN_PROFILE}).</li>
 *   <li><b>Skills:</b> {@link DictionarySkillMatcher#match(String)} mentions (boundary and ambiguous-name
 *       rules) must be allowed skills. A mention that lies entirely inside a longer mention of an allowed
 *       skill ("Spring Boot" when only Spring Boot is allowed) doesn't count.</li>
 *   <li><b>Contact details:</b> email addresses (the {@code MatchExplanationValidator} pattern), URLs
 *       ({@code http(s)://}, {@code www.}) and phone numbers (9..15 digits). Their digits are not also
 *       reported as numbers.</li>
 *   <li><b>Fails closed:</b> without a vocabulary every non-blank text gets one
 *       {@link FindingCode#SKILL_NOT_IN_PROFILE} problem with the value {@link #UNCHECKED_SKILLS}.</li>
 * </ul>
 */
public final class FactCheck {

    private static final Logger log = LoggerFactory.getLogger(FactCheck.class);

    /** The value of the skill problem reported when the vocabulary is unavailable (fail closed). */
    public static final String UNCHECKED_SKILLS = "(skill check unavailable)";

    static final Pattern EMAIL = Pattern.compile("[\\w.+-]+@[\\w-]+\\.[\\w.-]+");
    static final Pattern URL = Pattern.compile("(?i)(?<![\\w.])(?:https?://|www\\.)\\S+");
    static final Pattern PHONE = Pattern.compile("(?<![\\w+])\\+?\\(?\\d[\\d ()-]{6,}\\d(?!\\w)");
    static final int PHONE_MIN_DIGITS = 9;
    static final int PHONE_MAX_DIGITS = 15;

    /** Same token boundaries as {@link DictionarySkillMatcher}. */
    private static final String BEFORE = "(?<![\\p{L}\\p{N}+#.])";
    private static final String AFTER = "(?![\\p{L}\\p{N}+#])";
    private static final Pattern YEAR = Pattern.compile("(?:19|20)\\d{2}");
    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{M}]+");
    private static final Pattern DIGIT_GROUPS = Pattern.compile("\\D+");
    private static final String TRAILING_PUNCTUATION = ".,;:!?)]}'\"";
    /** Skipped when looking back for the start of a sentence. */
    private static final String SENTENCE_LOOKBACK_SKIP = "\"'“”‘’([{«»—–-";
    private static final String SENTENCE_END = ".!?:;…";
    private static final Set<String> NAME_STOP_WORDS = Set.of("i", "dear",
            "monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday",
            "january", "february", "march", "april", "may", "june", "july", "august", "september", "october",
            "november", "december");

    /** One failed check: the code and the offending token as written. */
    public record Problem(FindingCode code, String value) {
    }

    private final SkillDictionary.Snapshot vocabulary;
    private final Map<UUID, List<String>> termsBySkill;
    private final Map<String, Pattern> termPatterns = new ConcurrentHashMap<>();

    /** @param vocabulary the skill vocabulary; null makes every skill check fail closed */
    public FactCheck(SkillDictionary.Snapshot vocabulary) {
        this.vocabulary = vocabulary != null && vocabulary.matcher() != null ? vocabulary : null;
        this.termsBySkill = this.vocabulary == null ? Map.of() : termsBySkill(this.vocabulary);
    }

    /** A check without a vocabulary (every skill check fails closed). */
    public static FactCheck unavailable() {
        return new FactCheck(null);
    }

    /**
     * Loads the vocabulary (for example {@code dictionary::refresh}). A failure or a null snapshot gives a
     * fail-closed check and one WARN line naming the exception class only.
     */
    public static FactCheck load(Supplier<SkillDictionary.Snapshot> loader) {
        try {
            SkillDictionary.Snapshot snapshot = loader == null ? null : loader.get();
            if (snapshot == null || snapshot.matcher() == null) {
                log.warn("Tailoring fact check: no skill vocabulary is available; skill checks fail closed");
                return unavailable();
            }
            return new FactCheck(snapshot);
        } catch (RuntimeException e) {
            log.warn("Tailoring fact check: the skill vocabulary could not be loaded ({}); skill checks fail closed",
                    e.getClass().getSimpleName());
            return unavailable();
        }
    }

    public boolean available() {
        return vocabulary != null;
    }

    /** The vocabulary snapshot, or null when unavailable. */
    public SkillDictionary.Snapshot vocabulary() {
        return vocabulary;
    }

    // ------------------------------------------------------------------ vocabulary helpers

    /** The skills mentioned in {@code text} by the matcher's rules; empty without a vocabulary. */
    public Set<UUID> mentions(String text) {
        if (vocabulary == null || text == null || text.isBlank()) {
            return Set.of();
        }
        Set<UUID> ids = new HashSet<>();
        for (SkillMention m : vocabulary.matcher().match(text)) {
            ids.add(m.skillId());
        }
        return ids;
    }

    /** The skill a name or alias refers to (case-insensitive); empty without a vocabulary. */
    public Optional<UUID> resolve(String nameOrAlias) {
        return vocabulary == null ? Optional.empty() : vocabulary.resolve(nameOrAlias);
    }

    // ------------------------------------------------------------------ the checks

    /**
     * All problems in {@code text}, in this order: contact details, numbers (and years), skills.
     *
     * @param allowedNumbers canonical numbers that may appear
     * @param allowedSkills  skill ids that may be mentioned (their mentions also exempt digits by span)
     * @param allowedYears   null: years are ordinary numbers; else 4-digit years must be in this set
     */
    public List<Problem> check(String text, Set<String> allowedNumbers, Set<UUID> allowedSkills,
                               Set<String> allowedYears) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        Set<Problem> out = new LinkedHashSet<>();
        List<int[]> contactSpans = contactSpans(text);
        for (int[] s : contactSpans) {
            out.add(new Problem(FindingCode.CONTACT_DETAILS, stripTrailing(text.substring(s[0], s[1]))));
        }
        String masked = mask(text, contactSpans);
        Set<UUID> skills = allowedSkills == null ? Set.of() : allowedSkills;
        out.addAll(numbers(masked, allowedNumbers == null ? Set.of() : allowedNumbers, skills, allowedYears));
        out.addAll(skills(masked, skills));
        return List.copyOf(out);
    }

    /** Email addresses, URLs and phone numbers in {@code text} (tokens as written). */
    public List<String> contactDetails(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        Set<String> out = new LinkedHashSet<>();
        for (int[] s : contactSpans(text)) {
            out.add(stripTrailing(text.substring(s[0], s[1])));
        }
        return List.copyOf(out);
    }

    /** Spans of email addresses, URLs and phone numbers, in pattern order, without overlaps. */
    private static List<int[]> contactSpans(String text) {
        List<int[]> spans = new ArrayList<>();
        for (Pattern p : List.of(EMAIL, URL, PHONE)) {
            Matcher m = p.matcher(text);
            while (m.find()) {
                if (p == PHONE && !phoneLike(m.group())) {
                    continue;
                }
                if (overlapsAny(m.start(), m.end(), spans)) {
                    continue;                                  // a URL that contains an email, etc.
                }
                spans.add(new int[] {m.start(), m.end()});
            }
        }
        return spans;
    }

    private List<Problem> numbers(String text, Set<String> allowed, Set<UUID> allowedSkills, Set<String> years) {
        List<Problem> out = new ArrayList<>();
        List<int[]> skillSpans = null;                         // computed only when a token needs it
        Matcher m = ProfileText.NUMBER.matcher(text);
        while (m.find()) {
            String canonical = ProfileText.canonicalNumber(m.group());
            boolean year = years != null && YEAR.matcher(canonical).matches();
            if (year ? years.contains(canonical) : allowed.contains(canonical)) {
                continue;
            }
            if (skillSpans == null) {
                skillSpans = spansOf(text, allowedSkills);
            }
            if (inside(m.start(), m.end(), skillSpans)) {
                continue;
            }
            String token = m.end() < text.length() && text.charAt(m.end()) == '%' ? m.group() + "%" : m.group();
            out.add(new Problem(year ? FindingCode.YEAR_NOT_IN_PROFILE : FindingCode.NUMBER_NOT_IN_PROFILE, token));
        }
        Matcher w = WORD.matcher(text);
        while (w.find()) {
            Integer value = ProfileText.numberWordValue(w.group());
            if (value == null || value < 2 || allowed.contains(Integer.toString(value))) {
                continue;
            }
            out.add(new Problem(FindingCode.NUMBER_NOT_IN_PROFILE, w.group()));
        }
        return out;
    }

    private List<Problem> skills(String text, Set<UUID> allowed) {
        if (vocabulary == null) {
            return List.of(new Problem(FindingCode.SKILL_NOT_IN_PROFILE, UNCHECKED_SKILLS));
        }
        List<SkillMention> foreign = vocabulary.matcher().match(text).stream()
                .filter(m -> !allowed.contains(m.skillId()))
                .toList();
        if (foreign.isEmpty()) {
            return List.of();
        }
        List<int[]> allowedSpans = spansOf(text, allowed);
        List<Problem> out = new ArrayList<>();
        for (SkillMention mention : foreign) {
            List<int[]> own = spansOf(text, Set.of(mention.skillId()));
            if (own.isEmpty()) {
                out.add(new Problem(FindingCode.SKILL_NOT_IN_PROFILE, mention.name()));   // can't locate: fail closed
                continue;
            }
            for (int[] span : own) {
                if (!insideLonger(span, allowedSpans)) {
                    out.add(new Problem(FindingCode.SKILL_NOT_IN_PROFILE, text.substring(span[0], span[1])));
                    break;
                }
            }
        }
        return out;
    }

    /**
     * Capitalised tokens that may name a person, employer or product the profile doesn't know
     * ({@code UNKNOWN_NAME}): not sentence-initial, not all capitals (acronyms), not in {@code allowedLower}
     * (see {@link #nameTokens}), not a stop word (I, Dear, weekdays, months) and not a vocabulary skill.
     */
    public List<String> unknownNames(String text, Set<String> allowedLower) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        Set<String> out = new LinkedHashSet<>();
        Matcher m = WORD.matcher(text);
        while (m.find()) {
            String token = m.group();
            if (!Character.isUpperCase(token.codePointAt(0)) || token.equals(token.toUpperCase(Locale.ROOT))) {
                continue;
            }
            String lower = token.toLowerCase(Locale.ROOT);
            if (NAME_STOP_WORDS.contains(lower) || allowedLower.contains(lower) || sentenceInitial(text, m.start())
                    || resolve(token).isPresent()) {
                continue;
            }
            out.add(token);
        }
        return List.copyOf(out);
    }

    /** The lower-case letter tokens of {@code text}, as {@link #unknownNames} splits them. */
    public static Set<String> nameTokens(String text) {
        Set<String> out = new HashSet<>();
        if (text == null) {
            return out;
        }
        Matcher m = WORD.matcher(text);
        while (m.find()) {
            out.add(m.group().toLowerCase(Locale.ROOT));
        }
        return out;
    }

    /** Share (0..1) of the content words of {@code text} found in {@code sourceWords}; 1 when it has none. */
    public static double overlap(String text, Set<String> sourceWords) {
        List<String> words = ProfileText.contentWords(text);
        if (words.isEmpty()) {
            return 1.0;
        }
        int found = 0;
        for (String w : words) {
            if (sourceWords.contains(w)) {
                found++;
            }
        }
        return (double) found / words.size();
    }

    // ------------------------------------------------------------------ helpers

    private static boolean phoneLike(String candidate) {
        String digits = candidate.replaceAll("\\D", "");
        if (digits.length() < PHONE_MIN_DIGITS || digits.length() > PHONE_MAX_DIGITS) {
            return false;
        }
        // "2019 2020 2021" is a list of years, not a phone number
        return !Arrays.stream(DIGIT_GROUPS.split(candidate.strip()))
                .filter(g -> !g.isEmpty())
                .allMatch(g -> YEAR.matcher(g).matches());
    }

    private static boolean sentenceInitial(String text, int start) {
        for (int i = start - 1; i >= 0; i--) {
            char c = text.charAt(i);
            if (c == '\n') {
                return true;
            }
            if (Character.isWhitespace(c) || SENTENCE_LOOKBACK_SKIP.indexOf(c) >= 0) {
                continue;
            }
            return SENTENCE_END.indexOf(c) >= 0;
        }
        return true;
    }

    /** Bounded occurrences of every name and alias of the skills, as [start, end). */
    private List<int[]> spansOf(String text, Set<UUID> skillIds) {
        List<int[]> out = new ArrayList<>();
        if (skillIds.isEmpty() || termsBySkill.isEmpty()) {
            return out;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        for (UUID id : skillIds) {
            for (String term : termsBySkill.getOrDefault(id, List.of())) {
                if (!lower.contains(term.toLowerCase(Locale.ROOT))) {
                    continue;
                }
                Matcher m = termPattern(term).matcher(text);
                while (m.find()) {
                    out.add(new int[] {m.start(), m.end()});
                }
            }
        }
        return out;
    }

    /**
     * Terms of {@value DictionarySkillMatcher#CASE_SENSITIVE_MAX_LENGTH} characters or fewer match
     * case-sensitively unless they are all lower case (aliases are stored lower-cased); longer ones ignore case.
     */
    private Pattern termPattern(String term) {
        return termPatterns.computeIfAbsent(term, t -> {
            boolean caseSensitive = t.length() <= DictionarySkillMatcher.CASE_SENSITIVE_MAX_LENGTH
                    && !t.equals(t.toLowerCase(Locale.ROOT));
            int flags = caseSensitive ? 0 : Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
            return Pattern.compile(BEFORE + Pattern.quote(t) + AFTER, flags);
        });
    }

    private static Map<UUID, List<String>> termsBySkill(SkillDictionary.Snapshot snapshot) {
        Map<UUID, Set<String>> terms = new HashMap<>();
        snapshot.names().forEach((id, name) -> add(terms, id, name));
        snapshot.byKey().forEach((key, id) -> add(terms, id, key));
        Map<UUID, List<String>> out = new HashMap<>();
        terms.forEach((id, set) -> out.put(id, List.copyOf(set)));
        return Collections.unmodifiableMap(out);
    }

    private static void add(Map<UUID, Set<String>> terms, UUID id, String term) {
        if (id != null && term != null && !term.isBlank()) {
            terms.computeIfAbsent(id, k -> new LinkedHashSet<>()).add(term.strip());
        }
    }

    private static boolean inside(int start, int end, List<int[]> spans) {
        for (int[] s : spans) {
            if (s[0] <= start && end <= s[1]) {
                return true;
            }
        }
        return false;
    }

    private static boolean insideLonger(int[] span, List<int[]> others) {
        for (int[] o : others) {
            if (o[0] <= span[0] && span[1] <= o[1] && (o[1] - o[0]) > (span[1] - span[0])) {
                return true;
            }
        }
        return false;
    }

    private static boolean overlapsAny(int start, int end, List<int[]> spans) {
        for (int[] s : spans) {
            if (start < s[1] && s[0] < end) {
                return true;
            }
        }
        return false;
    }

    private static String mask(String text, List<int[]> spans) {
        if (spans.isEmpty()) {
            return text;
        }
        char[] chars = text.toCharArray();
        for (int[] s : spans) {
            Arrays.fill(chars, s[0], s[1], ' ');
        }
        return new String(chars);
    }

    private static String stripTrailing(String token) {
        int end = token.length();
        while (end > 1 && TRAILING_PUNCTUATION.indexOf(token.charAt(end - 1)) >= 0) {
            end--;
        }
        return token.substring(0, end);
    }
}
