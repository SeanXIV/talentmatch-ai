package com.talentmatch.profile;

import com.talentmatch.profile.ProfileDocument.Certification;
import com.talentmatch.profile.ProfileDocument.Education;
import com.talentmatch.profile.ProfileDocument.Experience;
import com.talentmatch.profile.ProfileDocument.Project;
import com.talentmatch.profile.ProfileDocument.SkillEntry;
import java.text.Normalizer;
import java.time.Clock;
import java.time.Year;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Never-invent check for an AI-extracted draft: every name the model returned must appear in the
 * CV text. Matching ignores case, punctuation and spacing ("Node.js" = "node js"), and works on
 * whole words, so "Java" is not found inside "JavaScript". Anything not found becomes a warning
 * (the owner reviews the draft), never a silent deletion. Pure: no I/O.
 *
 * <p>One exception: a skill's years of experience are dropped (set to null, with a warning)
 * unless the number is written in the CV within {@value #YEARS_WINDOW} characters of the skill
 * name. The prompt forbids estimating years, and years feed scoring explanations, so an
 * unsupported number must not reach the profile unless the owner types it back in.
 */
public final class ResumeGrounding {

    /** How far (in normalized characters) from a skill name a stated number of years may be. */
    static final int YEARS_WINDOW = 60;

    private static final Pattern NON_WORD = Pattern.compile("[^\\p{L}\\p{N}+#]+");
    private static final Pattern FORMAT = Pattern.compile("\\p{Cf}");
    private static final Map<Integer, String> NUMBER_WORDS = Map.ofEntries(Map.entry(1, "one"), Map.entry(2, "two"),
            Map.entry(3, "three"), Map.entry(4, "four"), Map.entry(5, "five"), Map.entry(6, "six"),
            Map.entry(7, "seven"), Map.entry(8, "eight"), Map.entry(9, "nine"), Map.entry(10, "ten"),
            Map.entry(11, "eleven"), Map.entry(12, "twelve"), Map.entry(15, "fifteen"), Map.entry(20, "twenty"));

    /** The draft after grounding (ungrounded years removed) and the warnings for the owner. */
    public record Result(ProfileDocument document, List<ProfileWarning> warnings) {
    }

    private ResumeGrounding() {
    }

    /** Warnings only (the document is not changed); see {@link #apply}. */
    public static List<ProfileWarning> check(ProfileDocument doc, String cvText) {
        return apply(doc, cvText, Clock.systemUTC()).warnings();
    }

    /**
     * Grounds the draft against the CV text: warnings for names not found, for numbers, date
     * years, contact details and links not in the CV, and for reworded highlights
     * ({@link ResumeFactCheck}); skill years removed unless stated near the skill name.
     *
     * @param clock today's date, for "current" roles in the career-span check
     */
    public static Result apply(ProfileDocument doc, String cvText, Clock clock) {
        Haystack haystack = new Haystack(cvText);
        List<ProfileWarning> warnings = new ArrayList<>();
        doc = groundYears(doc, haystack, warnings);

        for (int i = 0; i < doc.skills().size(); i++) {
            SkillEntry s = doc.skills().get(i);
            require(haystack, "skills[" + i + "].name", s.name(), "skill", warnings);
        }
        for (int i = 0; i < doc.experience().size(); i++) {
            Experience e = doc.experience().get(i);
            require(haystack, "experience[" + i + "].company", e.company(), "employer", warnings);
            require(haystack, "experience[" + i + "].title", e.title(), "job title", warnings);
            for (int t = 0; t < e.technologies().size(); t++) {
                require(haystack, "experience[" + i + "].technologies[" + t + "]", e.technologies().get(t),
                        "technology", warnings);
            }
        }
        for (int i = 0; i < doc.projects().size(); i++) {
            Project p = doc.projects().get(i);
            require(haystack, "projects[" + i + "].name", p.name(), "project", warnings);
            for (int t = 0; t < p.technologies().size(); t++) {
                require(haystack, "projects[" + i + "].technologies[" + t + "]", p.technologies().get(t),
                        "technology", warnings);
            }
        }
        for (int i = 0; i < doc.certifications().size(); i++) {
            Certification c = doc.certifications().get(i);
            require(haystack, "certifications[" + i + "].name", c.name(), "certification", warnings);
            require(haystack, "certifications[" + i + "].issuer", c.issuer(), "issuer", warnings);
        }
        for (int i = 0; i < doc.education().size(); i++) {
            Education e = doc.education().get(i);
            require(haystack, "education[" + i + "].institution", e.institution(), "institution", warnings);
            require(haystack, "education[" + i + "].qualification", e.qualification(), "qualification", warnings);
        }
        checkYears(doc, warnings, Year.now(clock).getValue());
        warnings.addAll(ResumeFactCheck.check(doc, cvText));
        return new Result(doc, List.copyOf(warnings));
    }

    /**
     * NFKC (ligatures, full-width forms), format characters removed (soft hyphens, zero-width
     * spaces), lower case, punctuation to spaces, single spaces. Keeps + and # (C++, C#).
     */
    static String key(String s) {
        if (s == null) {
            return "";
        }
        String t = FORMAT.matcher(Normalizer.normalize(s, Normalizer.Form.NFKC)).replaceAll("");
        return NON_WORD.matcher(t.toLowerCase(Locale.ROOT)).replaceAll(" ").strip();
    }

    /** Years not written near the skill name become null, each with a warning. */
    private static ProfileDocument groundYears(ProfileDocument doc, Haystack haystack, List<ProfileWarning> warnings) {
        List<SkillEntry> skills = new ArrayList<>(doc.skills());
        boolean changed = false;
        for (int i = 0; i < skills.size(); i++) {
            SkillEntry s = skills.get(i);
            if (s.years() == null || haystack.yearsNear(s.name(), s.years())) {
                continue;
            }
            warnings.add(new ProfileWarning("skills[" + i + "].years", String.valueOf(s.years()),
                    "Your CV doesn't state " + s.years() + " years of " + s.name() + " next to the skill, so the "
                            + "number was removed (the AI may have estimated it). Type it back in if it is correct."));
            skills.set(i, new SkillEntry(s.name(), null));
            changed = true;
        }
        if (!changed) {
            return doc;
        }
        return new ProfileDocument(doc.fullName(), doc.email(), doc.phone(), doc.location(), doc.headline(),
                doc.summary(), doc.links(), doc.experience(), doc.projects(), List.copyOf(skills),
                doc.certifications(), doc.education(), doc.languages());
    }

    private static void require(Haystack haystack, String path, String value, String what,
                                List<ProfileWarning> warnings) {
        if (value == null) {
            return;
        }
        if (haystack.contains(value)) {
            return;
        }
        warnings.add(new ProfileWarning(path, value, "This " + what + " was not found in your CV text. "
                + "Check it is correct (the AI may have reworded or invented it) before saving."));
    }

    /** The CV text, searchable by whole words or by 1–3 adjacent words written together. */
    private static final class Haystack {

        private final String spaced;
        private final Set<String> joined = new HashSet<>();

        Haystack(String text) {
            String k = key(text);
            spaced = " " + k + " ";
            String[] words = k.isEmpty() ? new String[0] : k.split(" ");
            for (int i = 0; i < words.length; i++) {
                StringBuilder sb = new StringBuilder();
                for (int n = 0; n < 3 && i + n < words.length; n++) {
                    sb.append(words[i + n]);
                    joined.add(sb.toString());
                }
            }
        }

        /** "Node.js" matches "NodeJS" and "node js"; "Java" does not match "JavaScript". */
        boolean contains(String value) {
            String needle = key(value);
            return needle.isEmpty() || spaced.contains(" " + needle + " ") || joined.contains(needle.replace(" ", ""));
        }

        /**
         * True if {@code years} (digits, or the word for small numbers) appears as a whole word
         * within {@value #YEARS_WINDOW} characters of a whole-word occurrence of the skill name.
         */
        boolean yearsNear(String skill, int years) {
            String needle = key(skill);
            if (needle.isEmpty()) {
                return false;
            }
            Pattern number = Pattern.compile(" (" + years + (NUMBER_WORDS.containsKey(years)
                    ? "|" + NUMBER_WORDS.get(years) : "") + ")\\+? "); // "5 years", "5+ years", "five years"
            for (String form : needle.contains(" ") ? List.of(needle, needle.replace(" ", "")) : List.of(needle)) {
                String target = " " + form + " ";
                for (int at = spaced.indexOf(target); at >= 0; at = spaced.indexOf(target, at + 1)) {
                    int from = Math.max(0, at - YEARS_WINDOW);
                    int to = Math.min(spaced.length(), at + target.length() + YEARS_WINDOW);
                    // widen by one char each side so a number at the window edge keeps its spaces
                    Matcher m = number.matcher(spaced.substring(Math.max(0, from - 1), Math.min(spaced.length(), to + 1)));
                    if (m.find()) {
                        return true;
                    }
                }
            }
            return false;
        }
    }

    /** A stated number of years longer than the whole career shown in the CV is suspicious. */
    private static void checkYears(ProfileDocument doc, List<ProfileWarning> warnings, int now) {
        int first = Integer.MAX_VALUE;
        int last = Integer.MIN_VALUE;
        for (Experience e : doc.experience()) {
            Integer start = year(e.startDate());
            if (start == null) {
                continue;
            }
            Integer end = Boolean.TRUE.equals(e.current()) ? Integer.valueOf(now) : year(e.endDate());
            first = Math.min(first, start);
            last = Math.max(last, end == null ? start : end);
        }
        if (first == Integer.MAX_VALUE) {
            return;
        }
        int span = last - first + 1;
        for (int i = 0; i < doc.skills().size(); i++) {
            SkillEntry s = doc.skills().get(i);
            if (s.years() != null && s.years() > span) {
                warnings.add(new ProfileWarning("skills[" + i + "].years", String.valueOf(s.years()),
                        s.years() + " years of " + s.name() + " is longer than the career shown in your CV ("
                                + span + " years). Check the number."));
            }
        }
    }

    private static Integer year(String date) {
        return date == null || date.length() < 4 ? null : Integer.valueOf(date.substring(0, 4));
    }
}
