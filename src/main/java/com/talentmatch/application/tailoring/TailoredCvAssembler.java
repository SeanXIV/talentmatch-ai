package com.talentmatch.application.tailoring;

import com.talentmatch.application.content.Bullet;
import com.talentmatch.application.content.CertificationItem;
import com.talentmatch.application.content.EducationItem;
import com.talentmatch.application.content.ExperienceItem;
import com.talentmatch.application.content.Finding;
import com.talentmatch.application.content.FindingCode;
import com.talentmatch.application.content.LanguageItem;
import com.talentmatch.application.content.ProjectItem;
import com.talentmatch.application.content.TailoredCvContent;
import com.talentmatch.profile.ProfileDocument.Certification;
import com.talentmatch.profile.ProfileDocument.Education;
import com.talentmatch.profile.ProfileDocument.Experience;
import com.talentmatch.profile.ProfileDocument.Language;
import com.talentmatch.profile.ProfileDocument.Project;
import com.talentmatch.profile.ProfileNormalizer;
import com.talentmatch.profile.ProfileText;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Builds a tailored CV from the model's answer (§4.3, decisions e, f, g, h). Pure; one instance per call.
 *
 * <ol>
 *   <li>Header, education and languages are copied from the profile version.</li>
 *   <li>Experience: every profile role, in profile order, with the model's highlights for it (at most
 *       {@value #MAX_ROLE_HIGHLIGHTS}); unknown, hidden, foreign or repeated refs are dropped; a role
 *       without highlights gets its first {@value #DEFAULT_ROLE_HIGHLIGHTS} shown originals
 *       ({@code ROLE_DEFAULTED}).</li>
 *   <li>Each highlight text (cut to {@value #MAX_HIGHLIGHT_CHARS} characters) is checked against that
 *       highlight's numbers and skills and for contact details; a failure reverts it to the original
 *       ({@code REVERTED}). Less than half of its content words from the original, its role or the job →
 *       {@code HEAVILY_REWORDED} (WARNING).</li>
 *   <li>Projects (at most {@value #MAX_PROJECTS}, {@value #MAX_PROJECT_HIGHLIGHTS} highlights each) and
 *       certifications in the model's order; unknown or repeated refs dropped.</li>
 *   <li>Skills resolved to the profile's spelling; others dropped; at most {@value #MAX_SKILLS}; none left →
 *       the profile's skills with the job's skills first ({@code SKILLS_DEFAULTED}).</li>
 *   <li>Headline and summary: null keeps the profile's; a failed check reverts to it; too long → cut
 *       ({@code LENGTH}, WARNING).</li>
 *   <li>Rejected when more than half of the model's items (role, project and certification refs,
 *       highlights, skills; at least {@value #MIN_ITEMS_FOR_REJECTION}) were dropped or reverted, or when the
 *       model returned no experience entry while the profile has roles.</li>
 * </ol>
 */
public final class TailoredCvAssembler {

    public static final int MAX_HIGHLIGHT_CHARS = 400;
    public static final int MAX_ROLE_HIGHLIGHTS = 6;
    public static final int DEFAULT_ROLE_HIGHLIGHTS = 2;
    public static final int MAX_PROJECTS = 4;
    public static final int MAX_PROJECT_HIGHLIGHTS = 4;
    public static final int MAX_SKILLS = 20;
    public static final int MAX_HEADLINE = 120;
    public static final int MAX_SUMMARY = 900;
    /** A rephrase sharing less than this share of content words with its sources is HEAVILY_REWORDED. */
    public static final double MIN_OVERLAP = 0.5;
    public static final int MIN_ITEMS_FOR_REJECTION = 4;

    static final String TRUNCATED_MESSAGE = "Older highlights weren't shown to the model because your profile is "
            + "long; they're not in this version.";

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final Pattern LEADING_BULLETS = Pattern.compile("^[-*•·\\s]+");

    private final ProfileFacts facts;
    private final JobFacts job;
    private final FactCheck check;
    private final Set<String> jobWords = new HashSet<>();
    private final List<Finding> findings = new ArrayList<>();
    private int items;
    private int repaired;

    private TailoredCvAssembler(ProfileFacts facts, JobFacts job, FactCheck check) {
        this.facts = facts;
        this.job = job;
        this.check = check;
        jobWords.addAll(ProfileText.contentWords(job.title()));
        job.requiredSkills().forEach(s -> jobWords.addAll(ProfileText.contentWords(s)));
        job.niceSkills().forEach(s -> jobWords.addAll(ProfileText.contentWords(s)));
    }

    /**
     * @param draft the parsed model answer
     * @param facts the profile version the prompt was built from
     * @param job   the job (title and skills: rewording vocabulary and the skills default order)
     * @param check the fact check (with the same vocabulary as {@code facts})
     */
    public static AssemblyResult<TailoredCvContent> assemble(TailoredCvDraft draft, ProfileFacts facts, JobFacts job,
                                                             FactCheck check) {
        if (draft == null) {
            return new AssemblyResult.Rejected<>("no answer");
        }
        JobFacts j = job == null ? new JobFacts(null, null, null, List.of(), List.of()) : job;
        return new TailoredCvAssembler(facts, j, check == null ? FactCheck.unavailable() : check).run(draft);
    }

    private AssemblyResult<TailoredCvContent> run(TailoredCvDraft draft) {
        List<TailoredCvDraft.Entry> modelRoles = nz(draft.experience());
        if (modelRoles.isEmpty() && !facts.experience().isEmpty()) {
            return new AssemblyResult.Rejected<>("no experience entries");
        }
        if (facts.truncated()) {
            findings.add(Finding.info(FindingCode.PROFILE_TRUNCATED, null, null, TRUNCATED_MESSAGE));
        }
        List<ExperienceItem> experience = experience(modelRoles);
        List<ProjectItem> projects = projects(nz(draft.projects()));
        List<CertificationItem> certifications = certifications(nz(draft.certifications()));
        List<String> skills = skills(nz(draft.skills()));
        String headline = freeText(draft.headline(), facts.profile().headline(), "headline", MAX_HEADLINE,
                facts.statedNumbers());
        String summary = freeText(draft.summary(), facts.profile().summary(), "summary", MAX_SUMMARY,
                facts.summaryNumbers());

        if (items >= MIN_ITEMS_FOR_REJECTION && repaired * 2 > items) {
            return new AssemblyResult.Rejected<>(repaired + " of " + items + " items dropped or reverted");
        }

        List<EducationItem> education = new ArrayList<>();
        for (int i = 0; i < facts.education().size(); i++) {
            Education d = facts.education().get(i);
            education.add(new EducationItem(facts.educationRefs().get(i), d.institution(), d.qualification(),
                    d.field(), d.startDate(), d.endDate()));
        }
        List<LanguageItem> languages = new ArrayList<>();
        for (Language l : facts.languages()) {
            languages.add(new LanguageItem(l.name(), l.level()));
        }
        TailoredCvContent content = new TailoredCvContent(facts.header(), headline, summary, skills, experience,
                projects, certifications, education, languages);
        return new AssemblyResult.Assembled<>(content, findings);
    }

    // ------------------------------------------------------------------ experience

    private List<ExperienceItem> experience(List<TailoredCvDraft.Entry> modelRoles) {
        Map<String, TailoredCvDraft.Entry> byRef = new HashMap<>();
        for (TailoredCvDraft.Entry entry : modelRoles) {
            items++;
            String ref = entry == null ? null : ref(entry.ref());
            if (ref == null || !ref.startsWith("E") || facts.indexOf(ref) < 0) {
                dropEntry(entry, FindingCode.UNKNOWN_REF, "experience", ref,
                        "The model referred to a role " + quoted(ref) + " that isn't in your profile; it was ignored.");
            } else if (byRef.containsKey(ref)) {
                dropEntry(entry, FindingCode.DUPLICATE_REF, "experience", ref,
                        "The model listed role " + ref + " twice; the repeat was ignored.");
            } else {
                byRef.put(ref, entry);
            }
        }
        List<ExperienceItem> out = new ArrayList<>();
        for (int i = 0; i < facts.experience().size(); i++) {
            Experience e = facts.experience().get(i);
            String ref = facts.experienceRefs().get(i);
            String path = "experience[" + i + "]";
            TailoredCvDraft.Entry entry = byRef.get(ref);
            List<Bullet> bullets = entry == null ? new ArrayList<>()
                    : bullets(nz(entry.highlights()), ref, path, MAX_ROLE_HIGHLIGHTS, "role");
            if (bullets.isEmpty()) {
                for (ProfileFacts.Highlight h : facts.highlightsOf(ref)) {
                    if (bullets.size() == DEFAULT_ROLE_HIGHLIGHTS) {
                        break;
                    }
                    if (h.shown()) {
                        bullets.add(new Bullet(h.ref(), h.text()));
                    }
                }
                if (!bullets.isEmpty()) {
                    findings.add(Finding.info(FindingCode.ROLE_DEFAULTED, path, ref, "The model chose no points for "
                            + "this role, so its first " + bullets.size() + " points from your profile are used."));
                }
            }
            out.add(new ExperienceItem(ref, e.title(), e.company(), e.location(), e.startDate(), e.endDate(),
                    e.current(), e.technologies(), bullets));
        }
        return out;
    }

    // ------------------------------------------------------------------ projects and certifications

    private List<ProjectItem> projects(List<TailoredCvDraft.Entry> modelProjects) {
        List<ProjectItem> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (TailoredCvDraft.Entry entry : modelProjects) {
            items++;
            String ref = entry == null ? null : ref(entry.ref());
            if (ref == null || !ref.startsWith("P") || facts.indexOf(ref) < 0) {
                dropEntry(entry, FindingCode.UNKNOWN_REF, "projects", ref,
                        "The model referred to a project " + quoted(ref) + " that isn't in your profile; it was ignored.");
                continue;
            }
            if (!seen.add(ref)) {
                dropEntry(entry, FindingCode.DUPLICATE_REF, "projects", ref,
                        "The model listed project " + ref + " twice; the repeat was ignored.");
                continue;
            }
            if (out.size() == MAX_PROJECTS) {
                items += nz(entry.highlights()).size();         // over the cap: left out, not a repair
                continue;
            }
            Project p = facts.projects().get(facts.indexOf(ref));
            String path = "projects[" + out.size() + "]";
            List<Bullet> bullets = bullets(nz(entry.highlights()), ref, path, MAX_PROJECT_HIGHLIGHTS, "project");
            out.add(new ProjectItem(ref, p.name(), p.description(), p.technologies(), p.url(), bullets));
        }
        return out;
    }

    private List<CertificationItem> certifications(List<String> modelRefs) {
        List<CertificationItem> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String raw : modelRefs) {
            items++;
            String ref = ref(raw);
            if (ref == null || !ref.startsWith("C") || facts.indexOf(ref) < 0) {
                repaired++;
                findings.add(Finding.info(FindingCode.UNKNOWN_REF, "certifications", ref, "The model referred to a "
                        + "certification " + quoted(ref) + " that isn't in your profile; it was ignored."));
                continue;
            }
            if (!seen.add(ref)) {
                repaired++;
                findings.add(Finding.info(FindingCode.DUPLICATE_REF, "certifications", ref,
                        "The model listed certification " + ref + " twice; the repeat was ignored."));
                continue;
            }
            Certification c = facts.certifications().get(facts.indexOf(ref));
            out.add(new CertificationItem(ref, c.name(), c.issuer(), c.issued(), c.expires()));
        }
        return out;
    }

    /** A dropped role or project entry: the entry and each of its highlights count as dropped items. */
    private void dropEntry(TailoredCvDraft.Entry entry, FindingCode code, String path, String ref, String message) {
        int highlights = entry == null ? 0 : nz(entry.highlights()).size();
        items += highlights;
        repaired += 1 + highlights;
        findings.add(Finding.info(code, path, ref, message));
    }

    // ------------------------------------------------------------------ highlights

    private List<Bullet> bullets(List<TailoredCvDraft.Highlight> modelHighlights, String parentRef, String path,
                                 int max, String parentKind) {
        List<Bullet> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        String listPath = path + ".highlights";
        for (TailoredCvDraft.Highlight h : modelHighlights) {
            items++;
            String ref = h == null ? null : ref(h.ref());
            ProfileFacts.Highlight fact = facts.highlight(ref);
            if (fact == null || !fact.shown()) {
                repaired++;
                findings.add(Finding.info(FindingCode.UNKNOWN_REF, listPath, ref, "The model referred to a point "
                        + quoted(ref) + " that isn't in your profile; it was ignored."));
            } else if (!fact.parentRef().equals(parentRef)) {
                repaired++;
                findings.add(Finding.info(FindingCode.UNKNOWN_REF, listPath, ref, "The model put point " + ref
                        + " under another " + parentKind + "; it was ignored."));
            } else if (!seen.add(ref)) {
                repaired++;
                findings.add(Finding.info(FindingCode.DUPLICATE_REF, listPath, ref,
                        "The model used point " + ref + " twice; the repeat was ignored."));
            } else if (out.size() < max) {
                out.add(text(fact, h.text(), listPath + "[" + out.size() + "]", parentKind));
            }
        }
        return out;
    }

    /** The checked rephrase of one highlight, or its original wording. */
    private Bullet text(ProfileFacts.Highlight fact, String raw, String path, String parentKind) {
        String original = fact.text();
        String t = clean(raw);
        if (t.isEmpty()) {
            return revert(fact, path, null, "an empty text");
        }
        t = ProfileNormalizer.cut(t, MAX_HIGHLIGHT_CHARS).strip();
        if (t.equals(clean(original))) {
            return new Bullet(fact.ref(), original);
        }
        for (FactCheck.Problem p : check.check(t, facts.numbers(fact.ref()), facts.skills(fact.ref()), null)) {
            if (FactCheck.UNCHECKED_SKILLS.equals(p.value())) {
                // fail closed: reverted, but not counted against the model (§4.4)
                findings.add(Finding.info(FindingCode.REVERTED, path, null, "Skills couldn't be checked (the skill "
                        + "list is unavailable), so your profile's wording is used."));
                return new Bullet(fact.ref(), original);
            }
            String reason = switch (p.code()) {
                case CONTACT_DETAILS -> "contact details";
                case SKILL_NOT_IN_PROFILE -> "a skill not in this " + parentKind;
                default -> "a number not in this point of your profile";
            };
            return revert(fact, path, p.value(), reason);
        }
        Set<String> sources = new HashSet<>(facts.words(fact.ref()));
        sources.addAll(jobWords);
        if (FactCheck.overlap(t, sources) < MIN_OVERLAP) {
            findings.add(Finding.warning(FindingCode.HEAVILY_REWORDED, path, null, "This point is heavily reworded. "
                    + "Check it still says what your profile says."));
        }
        return new Bullet(fact.ref(), t);
    }

    private Bullet revert(ProfileFacts.Highlight fact, String path, String value, String reason) {
        repaired++;
        findings.add(Finding.info(FindingCode.REVERTED, path, value, "The reworded point had " + reason
                + ", so your profile's wording is used."));
        return new Bullet(fact.ref(), fact.text());
    }

    // ------------------------------------------------------------------ skills

    private List<String> skills(List<String> modelSkills) {
        Set<String> out = new LinkedHashSet<>();
        Set<String> seen = new HashSet<>();
        for (String raw : modelSkills) {
            items++;
            String name = raw == null ? "" : clean(raw);
            var profileName = facts.profileSkill(name, check);
            if (profileName.isEmpty()) {
                repaired++;
                findings.add(Finding.info(FindingCode.SKILL_NOT_IN_PROFILE, "skills", name.isEmpty() ? null : name,
                        quoted(name.isEmpty() ? null : name) + " is not in your profile skills, so it was left out."));
                continue;
            }
            if (seen.add(profileName.get().toLowerCase(Locale.ROOT)) && out.size() < MAX_SKILLS) {
                out.add(profileName.get());
            }
        }
        if (!out.isEmpty()) {
            return List.copyOf(out);
        }
        List<String> defaults = defaultSkills();
        if (!defaults.isEmpty()) {
            findings.add(Finding.info(FindingCode.SKILLS_DEFAULTED, "skills", null, "No usable skills were chosen, so "
                    + "your profile's skills are listed, with the ones this job asks for first."));
        }
        return defaults;
    }

    /** The profile's skills, those among the job's skills first (stable), at most {@value #MAX_SKILLS}. */
    private List<String> defaultSkills() {
        Set<String> jobNames = new HashSet<>();
        Set<UUID> jobIds = new HashSet<>();
        for (String s : concat(job.requiredSkills(), job.niceSkills())) {
            jobNames.add(s.strip().toLowerCase(Locale.ROOT));
            check.resolve(s).ifPresent(jobIds::add);
        }
        List<String> first = new ArrayList<>();
        List<String> rest = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String name : facts.skillNames()) {
            if (!seen.add(name.toLowerCase(Locale.ROOT))) {
                continue;
            }
            boolean onJob = jobNames.contains(name.strip().toLowerCase(Locale.ROOT))
                    || check.resolve(name).map(jobIds::contains).orElse(false);
            (onJob ? first : rest).add(name);
        }
        first.addAll(rest);
        return List.copyOf(first.subList(0, Math.min(MAX_SKILLS, first.size())));
    }

    // ------------------------------------------------------------------ headline and summary

    private String freeText(String raw, String original, String field, int max, Set<String> allowedNumbers) {
        String result = original;
        String t = raw == null ? "" : clean(raw);
        if (!t.isEmpty() && !t.equals(clean(original))) {
            List<FactCheck.Problem> problems = check.check(t, allowedNumbers, facts.skillsGlobal(), null);
            if (problems.isEmpty()) {
                result = t;
            } else {
                FactCheck.Problem p = problems.get(0);
                String reason = switch (p.code()) {
                    case CONTACT_DETAILS -> "contact details";
                    case SKILL_NOT_IN_PROFILE -> "a skill not in your profile";
                    default -> "a number not in your profile";
                };
                findings.add(Finding.info(FindingCode.REVERTED, field, p.value(), "The reworded " + field + " had "
                        + reason + ", so your profile's " + field + " is used."));
            }
        }
        if (result != null && result.length() > max) {
            result = cutAtWord(result, max);
            findings.add(Finding.warning(FindingCode.LENGTH, field, null, "The " + field + " was longer than " + max
                    + " characters and was shortened."));
        }
        return result;
    }

    // ------------------------------------------------------------------ helpers

    /** Whitespace collapsed, leading bullet characters removed, trimmed; "" for null. */
    static String clean(String s) {
        if (s == null) {
            return "";
        }
        return LEADING_BULLETS.matcher(WHITESPACE.matcher(s).replaceAll(" ").strip()).replaceFirst("").strip();
    }

    /** "e1.h2", "[E1.H2]" → "E1.H2"; null for blank. */
    static String ref(String raw) {
        if (raw == null) {
            return null;
        }
        String r = raw.strip().replace("[", "").replace("]", "").strip().toUpperCase(Locale.ROOT);
        return r.isEmpty() ? null : r;
    }

    /** At most {@code max} characters, cut at the last space at or before the limit (hard cut if none). */
    static String cutAtWord(String text, int max) {
        if (text.length() <= max) {
            return text;
        }
        int cut = text.lastIndexOf(' ', max);
        String head = cut > 0 ? text.substring(0, cut) : ProfileNormalizer.cut(text, max);
        return head.stripTrailing();
    }

    private static String quoted(String value) {
        return value == null ? "(blank)" : "'" + ProfileNormalizer.cut(value, 60) + "'";
    }

    private static <T> List<T> nz(List<T> list) {
        return list == null ? List.of() : list;
    }

    private static List<String> concat(List<String> a, List<String> b) {
        List<String> out = new ArrayList<>(a);
        out.addAll(b);
        return out;
    }
}
