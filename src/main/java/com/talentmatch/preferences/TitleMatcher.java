package com.talentmatch.preferences;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Token-based job title matching (pure).
 * <ul>
 *   <li>Text is NFKC-normalized, lower-cased and split on anything but letters, digits, {@code +} and
 *       {@code #} ("C++", "C#" survive; "Sr." becomes "sr").</li>
 *   <li>"back-end"/"back end" → "backend"; the same for front end and full stack.</li>
 *   <li>For target titles, developer ≈ engineer ≈ programmer, and seniority words are ignored.</li>
 * </ul>
 */
public final class TitleMatcher {

    private static final Pattern SPLIT = Pattern.compile("[^\\p{L}\\p{N}+#]+");
    private static final Pattern BACK_FRONT_END = Pattern.compile("\\b(back|front)[\\s\\-]+end\\b",
            Pattern.UNICODE_CHARACTER_CLASS);
    private static final Pattern FULL_STACK = Pattern.compile("\\bfull[\\s\\-]+stack\\b", Pattern.UNICODE_CHARACTER_CLASS);

    /** Words that say how senior a role is; a target "Senior Java Developer" matches "Java Engineer". */
    static final Set<String> SENIORITY_WORDS = Set.of("intern", "internship", "junior", "jr", "graduate", "entry",
            "level", "senior", "sr", "lead", "staff", "principal", "intermediate", "mid");

    private TitleMatcher() {
    }

    /** True if any target matches (an empty target list matches every title). */
    public static boolean anyMatches(List<String> targets, String title) {
        if (targets == null || targets.isEmpty()) {
            return true;
        }
        Set<String> titleTokens = new HashSet<>(tokens(title, true));
        for (String target : targets) {
            if (matches(target, titleTokens)) {
                return true;
            }
        }
        return false;
    }

    /** True if every non-seniority token of the target (after synonyms) appears in the title. */
    public static boolean matches(String target, String title) {
        return matches(target, new HashSet<>(tokens(title, true)));
    }

    private static boolean matches(String target, Set<String> titleTokens) {
        List<String> wanted = tokens(target, true);
        wanted.removeIf(SENIORITY_WORDS::contains);
        return titleTokens.containsAll(wanted);
    }

    /** True if the keyword's tokens appear in the title as a consecutive run ("Sales" in "Sales Engineer"). */
    public static boolean containsKeyword(String title, String keyword) {
        List<String> k = tokens(keyword, false);
        return !k.isEmpty() && containsSequence(tokens(title, false), k);
    }

    /**
     * Normalized tokens of a text (mutable list). With {@code synonyms}, developer and programmer
     * become engineer.
     */
    public static List<String> tokens(String text, boolean synonyms) {
        List<String> out = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return out;
        }
        String s = Normalizer.normalize(text, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        s = BACK_FRONT_END.matcher(s).replaceAll("$1end");
        s = FULL_STACK.matcher(s).replaceAll("fullstack");
        for (String token : SPLIT.split(s)) {
            if (token.isEmpty()) {
                continue;
            }
            if (synonyms && (token.equals("developer") || token.equals("programmer"))) {
                token = "engineer";
            }
            out.add(token);
        }
        return out;
    }

    static boolean containsSequence(List<String> tokens, List<String> run) {
        if (run.isEmpty() || run.size() > tokens.size()) {
            return false;
        }
        outer:
        for (int i = 0; i + run.size() <= tokens.size(); i++) {
            for (int j = 0; j < run.size(); j++) {
                if (!tokens.get(i + j).equals(run.get(j))) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }
}
