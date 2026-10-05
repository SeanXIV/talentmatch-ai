package com.talentmatch.ai;

import com.talentmatch.domain.scoring.MatchEvaluation;
import com.talentmatch.domain.scoring.SkillHit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Normalizes and checks LLM output before it is shown or stored (stateless, thread-safe).
 *
 * <p>Normalize: trim, collapse whitespace, null lists become empty, strip leading bullet
 * characters, drop blank and duplicate items (case-insensitive). Ground: keep a strength only if
 * it names a matched skill and a gap only if it names a missing skill, then cap each list at 3.
 * Reject the whole record if it is null, a field is blank or out of bounds, or any field
 * contains an email address, a URL, or an echo of the prompt's tags/sections.
 */
public class MatchExplanationValidator {

    static final int MAX_HEADLINE = 100;
    static final int MIN_EXPLANATION = 20;
    static final int MAX_EXPLANATION = 700;
    static final int MAX_ITEM = 100;
    static final int MAX_ITEMS = 3;

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final Pattern LEADING_BULLETS = Pattern.compile("^[-*•\\s]+");
    private static final Pattern EMAIL = Pattern.compile("[\\w.+-]+@[\\w-]+\\.[\\w.-]+");

    /**
     * @return the normalized explanation, or empty if it must be rejected
     */
    public Optional<MatchExplanation> normalize(MatchExplanation raw, MatchEvaluation eval) {
        if (raw == null || eval == null) {
            return Optional.empty();
        }
        String headline = clean(raw.headline());
        String explanation = clean(raw.explanation());
        if (headline.isEmpty() || headline.length() > MAX_HEADLINE) {
            return Optional.empty();
        }
        if (explanation.isEmpty() || explanation.length() < MIN_EXPLANATION
                || explanation.length() > MAX_EXPLANATION) {
            return Optional.empty();
        }

        List<String> matchedNames = names(Stream.concat(
                eval.matchedRequired().stream(), eval.matchedNiceToHave().stream()).toList());
        List<String> missingNames = names(Stream.concat(
                eval.missingRequired().stream(), eval.missingNiceToHave().stream()).toList());
        List<String> strengths = grounded(items(raw.strengths()), matchedNames);
        List<String> gaps = grounded(items(raw.gaps()), missingNames);

        List<String> fields = new ArrayList<>(2 + strengths.size() + gaps.size());
        fields.add(headline);
        fields.add(explanation);
        fields.addAll(strengths);
        fields.addAll(gaps);
        for (String item : strengths) {
            if (item.length() > MAX_ITEM) {
                return Optional.empty();
            }
        }
        for (String item : gaps) {
            if (item.length() > MAX_ITEM) {
                return Optional.empty();
            }
        }
        for (String field : fields) {
            if (forbidden(field)) {
                return Optional.empty();
            }
        }
        return Optional.of(new MatchExplanation(headline, explanation, List.copyOf(strengths), List.copyOf(gaps)));
    }

    private static boolean forbidden(String field) {
        String lower = field.toLowerCase(Locale.ROOT);
        return EMAIL.matcher(field).find()
                || lower.contains("http://")
                || lower.contains("https://")
                || lower.contains("<job_description")
                || lower.contains("<candidate_summary")
                || field.contains("MATCH FACTS");
    }

    private static String clean(String value) {
        if (value == null) {
            return "";
        }
        return WHITESPACE.matcher(value).replaceAll(" ").strip();
    }

    /** Cleaned, de-bulleted, non-blank, de-duplicated items in their original order. */
    private static List<String> items(List<String> raw) {
        List<String> out = new ArrayList<>();
        if (raw == null) {
            return out;
        }
        Set<String> seen = new HashSet<>();
        for (String item : raw) {
            String c = LEADING_BULLETS.matcher(clean(item)).replaceFirst("").strip();
            if (c.isEmpty()) {
                continue;
            }
            if (seen.add(c.toLowerCase(Locale.ROOT))) {
                out.add(c);
            }
        }
        return out;
    }

    /** Items naming at least one of the skills (case-insensitive containment), capped. */
    private static List<String> grounded(List<String> items, List<String> skillNamesLower) {
        List<String> out = new ArrayList<>(MAX_ITEMS);
        for (String item : items) {
            if (out.size() == MAX_ITEMS) {
                break;
            }
            String lower = item.toLowerCase(Locale.ROOT);
            for (String skill : skillNamesLower) {
                if (!skill.isEmpty() && lower.contains(skill)) {
                    out.add(item);
                    break;
                }
            }
        }
        return out;
    }

    private static List<String> names(List<SkillHit> hits) {
        return hits.stream()
                .map(SkillHit::name)
                .filter(n -> n != null && !n.isBlank())
                .map(n -> n.strip().toLowerCase(Locale.ROOT))
                .toList();
    }
}
