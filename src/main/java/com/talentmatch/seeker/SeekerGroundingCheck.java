package com.talentmatch.seeker;

import com.talentmatch.ai.ExplanationGroundingCheck;
import com.talentmatch.ai.MatchExplanation;
import com.talentmatch.domain.scoring.MatchEvaluation;
import com.talentmatch.domain.scoring.SkillHit;
import com.talentmatch.feed.skills.DictionarySkillMatcher;
import com.talentmatch.feed.skills.SkillDictionary;
import com.talentmatch.feed.skills.SkillMention;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Free-text grounding for seeker explanations (Phase 6 §4.5, decision i). Rejects the whole output
 * (the caller then shows the template) when:
 * <ol>
 *   <li><b>Skills:</b> any field names a vocabulary skill (name or alias, by the
 *       {@link DictionarySkillMatcher} rules: token boundaries, case, ambiguous names) that is not
 *       among this job's skills (matched or missing, required or nice-to-have). A foreign skill is
 *       ignored only where every occurrence lies inside a longer mention of one of the job's own skills
 *       (or aliases), so "Spring Boot" on a Spring Boot job does not also count as "Spring", while
 *       "Spring Boot" on a Spring job is still rejected.</li>
 *   <li><b>Numbers:</b> a number ({@code \d+(?:[.,]\d+)?}) in the headline or explanation is not one of:
 *       the score percent, earned/max points, a matched skill's years, the matched/total required and
 *       nice-to-have counts, or a number in the job title, company, a job skill name or the engine's
 *       summary line (all of which are in MATCH FACTS). Digits inside a mention of one of the job's own
 *       skills or aliases ("k8s", "HTML5") are not numbers. Spelled-out numbers are not checked.</li>
 *   <li><b>Polarity</b> (best effort): a sentence says "you have/bring/know", names a missing skill,
 *       names no matched skill, and has no negation ("no", "not", "without", "lack", "missing", "yet",
 *       "n't", ...).</li>
 * </ol>
 * Fails closed: if the skill vocabulary is unavailable (no bean, or it can't be loaded) the output is
 * rejected. Stateless apart from the dictionary's snapshot; thread-safe. Never logs the text.
 */
@Component
public class SeekerGroundingCheck implements ExplanationGroundingCheck {

    private static final Logger log = LoggerFactory.getLogger(SeekerGroundingCheck.class);

    /** Same token boundaries as {@link DictionarySkillMatcher}. */
    private static final String BEFORE = "(?<![\\p{L}\\p{N}+#.])";
    private static final String AFTER = "(?![\\p{L}\\p{N}+#])";

    static final Pattern NUMBER = Pattern.compile("\\d+(?:[.,]\\d+)?");
    private static final Pattern THOUSANDS = Pattern.compile("\\d{1,3}(?:,\\d{3})+");
    private static final Pattern AFFIRMATIVE = Pattern.compile("(?iu)\\byou (?:have|bring|know)\\b");
    private static final Pattern NEGATION = Pattern.compile(
            "(?iu)\\b(?:no|not|none|never|without|lack|lacks|lacking|missing|yet)\\b|n['’]t\\b");
    /** Sentence ends: terminal punctuation followed by whitespace, or a line break. */
    private static final Pattern SENTENCE_END = Pattern.compile("(?<=[.!?;])\\s+|\\n+");

    private final ObjectProvider<SkillDictionary> dictionary;

    public SeekerGroundingCheck(ObjectProvider<SkillDictionary> dictionary) {
        this.dictionary = dictionary;
    }

    @Override
    public boolean accept(MatchExplanation value, MatchEvaluation eval, String jobTitle, String company) {
        if (value == null || eval == null) {
            return false;
        }
        Optional<SkillDictionary.Snapshot> vocabulary = vocabulary();
        if (vocabulary.isEmpty()) {
            return false;                                      // fail closed (§11 Q4)
        }
        SkillDictionary.Snapshot snap = vocabulary.get();
        return skillsGrounded(value, eval, snap)
                && numbersGrounded(value, eval, jobTitle, company, snap)
                && polarityOk(value, eval, snap.matcher());
    }

    // ------------------------------------------------------------------ vocabulary

    private Optional<SkillDictionary.Snapshot> vocabulary() {
        SkillDictionary dict = dictionary.getIfAvailable();
        if (dict == null) {
            log.warn("Seeker grounding check: no skill dictionary is available; rejecting the explanation");
            return Optional.empty();
        }
        try {
            // refresh() is one fingerprint query when nothing changed; it picks up skills added outside
            // the feed processor (which is the only other caller), e.g. when the feed is disabled.
            SkillDictionary.Snapshot snap = dict.refresh();
            if (snap == null || snap.matcher() == null) {
                return Optional.empty();
            }
            return Optional.of(snap);
        } catch (RuntimeException e) {
            log.warn("Seeker grounding check: the skill vocabulary could not be loaded ({}); rejecting the "
                    + "explanation", e.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    // ------------------------------------------------------------------ skills

    static boolean skillsGrounded(MatchExplanation value, MatchEvaluation eval, SkillDictionary.Snapshot snap) {
        Set<UUID> allowed = new HashSet<>();
        allHits(eval).forEach(h -> {
            if (h.skillId() != null) {
                allowed.add(h.skillId());
            }
        });
        String text = String.join("\n", nz(value.headline()), nz(value.explanation()),
                String.join("\n", nzList(value.strengths())), String.join("\n", nzList(value.gaps())));
        List<SkillMention> foreign = snap.matcher().match(text).stream()
                .filter(m -> !allowed.contains(m.skillId()))
                .toList();
        if (foreign.isEmpty()) {
            return true;
        }
        // A foreign skill only counts outside a longer mention of one of the job's own skills:
        // "Spring Boot" on a Spring Boot job is not also "Spring".
        List<int[]> allowedSpans = new ArrayList<>();
        for (String term : termsOf(allowed, eval, snap)) {
            allowedSpans.addAll(spans(text, term));
        }
        for (SkillMention mention : foreign) {
            List<int[]> own = new ArrayList<>();
            for (String term : termsOf(Set.of(mention.skillId()), null, snap)) {
                own.addAll(spans(text, term));
            }
            if (own.isEmpty()) {
                return false;                                  // can't locate it: fail closed
            }
            for (int[] span : own) {
                if (!insideLonger(span, allowedSpans)) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Names and aliases of the skills (plus the breakdown's own names when {@code eval} is given).
     * With a null {@code snap}, only the breakdown's names.
     */
    private static Set<String> termsOf(Set<UUID> ids, MatchEvaluation eval, SkillDictionary.Snapshot snap) {
        Set<String> terms = new LinkedHashSet<>();
        if (eval != null) {
            allHits(eval).forEach(h -> addTerm(terms, h.name()));
        }
        if (snap == null) {
            return terms;
        }
        for (UUID id : ids) {
            addTerm(terms, snap.names().get(id));
        }
        for (Map.Entry<String, UUID> e : snap.byKey().entrySet()) {
            if (ids.contains(e.getValue())) {
                addTerm(terms, e.getKey());
            }
        }
        return terms;
    }

    private static void addTerm(Set<String> terms, String term) {
        if (term != null && !term.isBlank()) {
            terms.add(term.strip());
        }
    }

    /**
     * Bounded occurrences of {@code term} in {@code text} as [start, end). Terms of
     * {@value DictionarySkillMatcher#CASE_SENSITIVE_MAX_LENGTH} characters or fewer match
     * case-sensitively unless they are all lower case (aliases are stored lower-cased), longer ones
     * ignore case.
     */
    static List<int[]> spans(String text, String term) {
        boolean caseSensitive = term.length() <= DictionarySkillMatcher.CASE_SENSITIVE_MAX_LENGTH
                && !term.equals(term.toLowerCase(Locale.ROOT));
        int flags = caseSensitive ? 0 : Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
        List<int[]> out = new ArrayList<>();
        if (!text.toLowerCase(Locale.ROOT).contains(term.toLowerCase(Locale.ROOT))) {
            return out;
        }
        Matcher m = Pattern.compile(BEFORE + Pattern.quote(term) + AFTER, flags).matcher(text);
        while (m.find()) {
            out.add(new int[] {m.start(), m.end()});
        }
        return out;
    }

    /** True if {@code span} lies within a strictly longer span of {@code others}. */
    private static boolean insideLonger(int[] span, List<int[]> others) {
        for (int[] o : others) {
            if (o[0] <= span[0] && span[1] <= o[1] && (o[1] - o[0]) > (span[1] - span[0])) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ numbers

    /** {@link #numbersGrounded(MatchExplanation, MatchEvaluation, String, String, SkillDictionary.Snapshot)} exempting only the breakdown's own skill names (no aliases). */
    static boolean numbersGrounded(MatchExplanation value, MatchEvaluation eval, String jobTitle, String company) {
        return numbersGrounded(value, eval, jobTitle, company, null);
    }

    /**
     * Every number token in the headline and explanation must be allowed ({@link #allowedNumbers}),
     * except tokens inside a mention of one of the job's own skills or their aliases ("k8s" for
     * Kubernetes, "HTML5", "ES6"): those name a skill, not a quantity. Digits inside skills that are
     * not on the job are not exempted (those skills are rejected by {@link #skillsGrounded} anyway).
     *
     * @param snap the vocabulary for alias lookup; null exempts only the breakdown's skill names
     */
    static boolean numbersGrounded(MatchExplanation value, MatchEvaluation eval, String jobTitle, String company,
                                   SkillDictionary.Snapshot snap) {
        Set<String> allowed = allowedNumbers(eval, jobTitle, company);
        Set<String> skillTerms = termsOf(jobSkillIds(eval), eval, snap);
        for (String field : List.of(nz(value.headline()), nz(value.explanation()))) {
            List<int[]> skillSpans = null;                     // computed only when a token needs it
            Matcher m = NUMBER.matcher(field);
            while (m.find()) {
                if (allowed.contains(canonical(m.group()))) {
                    continue;
                }
                if (skillSpans == null) {
                    skillSpans = new ArrayList<>();
                    for (String term : skillTerms) {
                        skillSpans.addAll(spans(field, term));
                    }
                }
                if (!inside(m.start(), m.end(), skillSpans)) {
                    return false;
                }
            }
        }
        return true;
    }

    /** True if [start, end) lies within one of the spans. */
    private static boolean inside(int start, int end, List<int[]> spans) {
        for (int[] s : spans) {
            if (s[0] <= start && end <= s[1]) {
                return true;
            }
        }
        return false;
    }

    private static Set<UUID> jobSkillIds(MatchEvaluation eval) {
        Set<UUID> ids = new HashSet<>();
        allHits(eval).forEach(h -> {
            if (h.skillId() != null) {
                ids.add(h.skillId());
            }
        });
        return ids;
    }

    static Set<String> allowedNumbers(MatchEvaluation eval, String jobTitle, String company) {
        Set<String> allowed = new HashSet<>();
        allowed.add(Long.toString(Math.round(eval.score() * 100d)));
        if (eval.maxPoints() > 0) {
            allowed.add(Long.toString(Math.round(eval.earnedPoints() * 100d / eval.maxPoints())));
        }
        allowed.add(Integer.toString(eval.earnedPoints()));
        allowed.add(Integer.toString(eval.maxPoints()));
        int r = eval.matchedRequired().size();
        int n = eval.matchedNiceToHave().size();
        allowed.add(Integer.toString(r));
        allowed.add(Integer.toString(r + eval.missingRequired().size()));
        allowed.add(Integer.toString(n));
        allowed.add(Integer.toString(n + eval.missingNiceToHave().size()));
        Stream.concat(eval.matchedRequired().stream(), eval.matchedNiceToHave().stream())
                .map(SkillHit::yearsExperience)
                .filter(y -> y != null)
                .forEach(y -> allowed.add(Integer.toString(y)));
        List<String> sources = new ArrayList<>();
        sources.add(jobTitle);
        sources.add(company);
        sources.add(eval.summary());
        allHits(eval).forEach(h -> sources.add(h.name()));
        for (String source : sources) {
            if (source == null) {
                continue;
            }
            Matcher m = NUMBER.matcher(source);
            while (m.find()) {
                allowed.add(canonical(m.group()));
            }
        }
        return allowed;
    }

    /**
     * Canonical form of a number token: "1,000" (thousands) → "1000"; "5.0" → "5"; "2,5" → "2.5";
     * leading zeros dropped. Unparseable tokens are returned as is.
     */
    static String canonical(String token) {
        String t = THOUSANDS.matcher(token).matches() ? token.replace(",", "") : token.replace(',', '.');
        try {
            BigDecimal d = new BigDecimal(t).stripTrailingZeros();
            return d.scale() <= 0 ? d.toBigInteger().toString() : d.toPlainString();
        } catch (NumberFormatException e) {
            return token;
        }
    }

    // ------------------------------------------------------------------ polarity

    static boolean polarityOk(MatchExplanation value, MatchEvaluation eval, DictionarySkillMatcher matcher) {
        List<SkillHit> matched = Stream.concat(eval.matchedRequired().stream(), eval.matchedNiceToHave().stream())
                .toList();
        List<SkillHit> missing = Stream.concat(eval.missingRequired().stream(), eval.missingNiceToHave().stream())
                .toList();
        if (missing.isEmpty()) {
            return true;
        }
        String text = nz(value.headline()) + "\n" + nz(value.explanation());
        for (String sentence : SENTENCE_END.split(text)) {
            if (!AFFIRMATIVE.matcher(sentence).find() || NEGATION.matcher(sentence).find()) {
                continue;
            }
            boolean namesMissing = missing.stream().anyMatch(h -> names(sentence, h, matcher));
            boolean namesMatched = matched.stream().anyMatch(h -> names(sentence, h, matcher));
            if (namesMissing && !namesMatched) {
                return false;
            }
        }
        return true;
    }

    private static boolean names(String sentence, SkillHit hit, DictionarySkillMatcher matcher) {
        return (hit.skillId() != null && matcher.mentionsSkill(sentence, hit.skillId()))
                || DictionarySkillMatcher.mentions(sentence, hit.name());
    }

    // ------------------------------------------------------------------ helpers

    private static Stream<SkillHit> allHits(MatchEvaluation eval) {
        return Stream.of(eval.matchedRequired(), eval.matchedNiceToHave(), eval.missingRequired(),
                eval.missingNiceToHave()).flatMap(List::stream);
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static List<String> nzList(List<String> list) {
        return list == null ? List.of() : list.stream().map(SeekerGroundingCheck::nz).toList();
    }
}
