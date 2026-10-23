package com.talentmatch.application.tailoring;

import com.talentmatch.application.content.CoverLetterContent;
import com.talentmatch.application.content.Finding;
import com.talentmatch.application.content.FindingCode;
import com.talentmatch.application.content.Severity;
import com.talentmatch.profile.ProfileNormalizer;
import com.talentmatch.profile.ProfileText;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Builds a cover letter from the model's paragraphs (§4.4, decisions h and p). Pure.
 *
 * <ul>
 *   <li>Fixed parts are set by the server: the header from the profile, company and job title from the
 *       job, the date, {@value #SALUTATION}, {@value #CLOSING} and the full name as signature. With
 *       {@code mentionNoticePeriod} and a notice period above 0 days, {@link #noticeSentence} is appended as
 *       its own last paragraph; without a known notice period: {@code NOTICE_PERIOD_UNKNOWN} (INFO).</li>
 *   <li>Per paragraph, numbers, years, vocabulary skills and contact details are checked; each failure is a
 *       BLOCKING finding naming the token, and the paragraph is kept. Capitalised names the profile and job
 *       don't know are {@code UNKNOWN_NAME} (WARNING); a total outside [min, max] words is {@code LENGTH}
 *       (WARNING).</li>
 *   <li>Rejected when there are no paragraphs, the words are below min/2 or above max × 1.5, or more than
 *       half of the paragraphs carry a BLOCKING finding.</li>
 * </ul>
 */
public final class CoverLetterAssembler {

    public static final String SALUTATION = "Dear Hiring Manager,";
    public static final String CLOSING = "Kind regards,";
    public static final int MAX_PARAGRAPHS = 6;
    public static final int MAX_PARAGRAPH_CHARS = 1500;

    /** Matches the fixed notice-period paragraph (exempt from the checks in owner edits). */
    static final Pattern NOTICE_SENTENCE =
            Pattern.compile("I am available to start after a notice period of \\d+ days\\.");

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final Pattern WORDS = Pattern.compile("\\S+");

    /**
     * Generation options.
     *
     * @param date                the letter date (today in the applications zone)
     * @param mentionNoticePeriod append the fixed notice-period sentence
     * @param noticePeriodDays    {@code job_preferences.noticePeriodDays}; null when unknown
     * @param minWords            {@code cover-letter.min-words}
     * @param maxWords            {@code cover-letter.max-words}
     */
    public record Options(LocalDate date, boolean mentionNoticePeriod, Integer noticePeriodDays, int minWords,
                          int maxWords) {
    }

    private CoverLetterAssembler() {
    }

    /** "I am available to start after a notice period of {n} days." */
    public static String noticeSentence(int days) {
        return "I am available to start after a notice period of " + days + " days.";
    }

    public static AssemblyResult<CoverLetterContent> assemble(CoverLetterDraft draft, ProfileFacts facts, JobFacts job,
                                                              FactCheck check, Options options) {
        if (draft == null || draft.paragraphs() == null) {
            return new AssemblyResult.Rejected<>("no paragraphs");
        }
        FactCheck fc = check == null ? FactCheck.unavailable() : check;
        JobFacts j = job == null ? new JobFacts(null, null, null, List.of(), List.of()) : job;
        List<String> paragraphs = new ArrayList<>();
        for (String raw : draft.paragraphs()) {
            String p = clean(raw);
            if (!p.isEmpty() && paragraphs.size() < MAX_PARAGRAPHS) {
                paragraphs.add(ProfileNormalizer.cut(p, MAX_PARAGRAPH_CHARS).strip());
            }
        }
        if (paragraphs.isEmpty()) {
            return new AssemblyResult.Rejected<>("no paragraphs");
        }
        int words = words(paragraphs);
        if (words * 2 < options.minWords() || words * 2 > options.maxWords() * 3) {
            return new AssemblyResult.Rejected<>(words + " words");
        }

        List<Finding> findings = new ArrayList<>();
        Checked checked = checkParagraphs(paragraphs, facts, j.title(), j.company(), j.description(), fc,
                Severity.BLOCKING);
        if (checked.paragraphsWithProblems() * 2 > paragraphs.size()) {
            return new AssemblyResult.Rejected<>(checked.paragraphsWithProblems() + " of " + paragraphs.size()
                    + " paragraphs not grounded");
        }
        findings.addAll(checked.findings());
        if (words < options.minWords() || words > options.maxWords()) {
            findings.add(Finding.warning(FindingCode.LENGTH, "paragraphs", Integer.toString(words), "The letter has "
                    + words + " words; " + options.minWords() + " to " + options.maxWords() + " reads best."));
        }
        if (options.mentionNoticePeriod()) {
            Integer days = options.noticePeriodDays();
            if (days != null && days > 0) {
                paragraphs.add(noticeSentence(days));
            } else {
                findings.add(Finding.info(FindingCode.NOTICE_PERIOD_UNKNOWN, "paragraphs", null, "Your notice period "
                        + "isn't set in your job preferences, so the letter doesn't mention it."));
            }
        }
        String fullName = facts.profile().fullName();
        CoverLetterContent content = new CoverLetterContent(facts.header(), j.company(), j.title(),
                options.date(), SALUTATION, paragraphs, CLOSING, fullName);
        return new AssemblyResult.Assembled<>(content, findings);
    }

    /**
     * The findings of the letter checks, and how many paragraphs had a number, year, skill or contact
     * problem (not counting the fail-closed "skill check unavailable" problem).
     */
    record Checked(List<Finding> findings, int paragraphsWithProblems) {
    }

    /**
     * Checks each paragraph (paths {@code paragraphs[i]}). Fact problems get {@code severity} (BLOCKING for AI
     * letters, WARNING for owner edits); {@code UNKNOWN_NAME} is always WARNING. Paragraphs that are exactly
     * the fixed notice-period sentence are skipped.
     *
     * @param description the job description (its words are allowed capitalised names); may be null
     */
    static Checked checkParagraphs(List<String> paragraphs, ProfileFacts facts, String jobTitle, String company,
                                   String description, FactCheck check, Severity severity) {
        Set<String> numbers = new HashSet<>(facts.numbersGlobal());
        numbers.addAll(ProfileText.wholeNumbers(jobTitle));
        numbers.addAll(ProfileText.wholeNumbers(company));
        Set<String> names = new HashSet<>(facts.capitalisedAllowed());
        names.addAll(FactCheck.nameTokens(jobTitle));
        names.addAll(FactCheck.nameTokens(company));
        names.addAll(FactCheck.nameTokens(description));

        List<Finding> findings = new ArrayList<>();
        int withProblems = 0;
        for (int i = 0; i < paragraphs.size(); i++) {
            String p = paragraphs.get(i);
            if (p == null || NOTICE_SENTENCE.matcher(p).matches()) {
                continue;
            }
            String path = "paragraphs[" + i + "]";
            List<FactCheck.Problem> problems = check.check(p, numbers, facts.skillsGlobal(), facts.allowedYears());
            // An unavailable vocabulary flags every paragraph, but is not the model's fault: it doesn't
            // count towards rejecting the answer.
            if (problems.stream().anyMatch(pr -> !FactCheck.UNCHECKED_SKILLS.equals(pr.value()))) {
                withProblems++;
            }
            for (FactCheck.Problem problem : problems) {
                findings.add(new Finding(severity, problem.code(), path, problem.value(), message(problem)));
            }
            for (String name : check.unknownNames(p, names)) {
                findings.add(Finding.warning(FindingCode.UNKNOWN_NAME, path, name, "'" + name + "' isn't in your "
                        + "profile or the job posting. Check it is right (a person, employer or product name)."));
            }
        }
        return new Checked(List.copyOf(findings), withProblems);
    }

    static String message(FactCheck.Problem p) {
        String v = p.value();
        return switch (p.code()) {
            case CONTACT_DETAILS -> "Contact details don't belong in the letter body; your header already has them.";
            case YEAR_NOT_IN_PROFILE -> "The year " + v + " is not a date in your profile. Check it before sending.";
            case SKILL_NOT_IN_PROFILE -> FactCheck.UNCHECKED_SKILLS.equals(v)
                    ? "Skills in this paragraph couldn't be checked (the skill list is unavailable). Check them "
                            + "before sending."
                    : "'" + v + "' is not in your profile's skills, roles or highlights. Check it before sending.";
            default -> "The number " + v + " is not in your profile. Check it before sending.";
        };
    }

    static String clean(String s) {
        return s == null ? "" : WHITESPACE.matcher(s).replaceAll(" ").strip();
    }

    static int words(List<String> paragraphs) {
        int n = 0;
        for (String p : paragraphs) {
            if (p == null) {
                continue;
            }
            var m = WORDS.matcher(p);
            while (m.find()) {
                n++;
            }
        }
        return n;
    }
}
