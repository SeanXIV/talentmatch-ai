package com.talentmatch.application.tailoring;

import com.talentmatch.application.content.Bullet;
import com.talentmatch.application.content.CertificationItem;
import com.talentmatch.application.content.CoverLetterContent;
import com.talentmatch.application.content.EducationItem;
import com.talentmatch.application.content.ExperienceItem;
import com.talentmatch.application.content.Finding;
import com.talentmatch.application.content.Header;
import com.talentmatch.application.content.LanguageItem;
import com.talentmatch.application.content.LinkItem;
import com.talentmatch.application.content.ProjectItem;
import com.talentmatch.application.content.Severity;
import com.talentmatch.application.content.TailoredCvContent;
import com.talentmatch.profile.ProfileDocument.Certification;
import com.talentmatch.profile.ProfileDocument.Education;
import com.talentmatch.profile.ProfileDocument.Experience;
import com.talentmatch.profile.ProfileDocument.Language;
import com.talentmatch.profile.ProfileDocument.Project;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Validates an owner edit of a CV or cover letter (§4.7, decision i). Pure.
 *
 * <ul>
 *   <li>Fact fields copied from the profile (header, titles, companies, locations, dates, {@code current},
 *       technologies, project, certification and education fields, languages) are read-only: they must equal
 *       the profile version's values after whitespace trimming. Experience entries can't be removed or
 *       reordered; education entries can't be removed.</li>
 *   <li>Every ref must exist in the profile version; a highlight ref must belong to its entry.</li>
 *   <li>Bullets 1..{@value #MAX_BULLET} characters; headline at most {@value #MAX_HEADLINE}, summary at most
 *       {@value #MAX_SUMMARY}; skills must be profile skills (output under the profile's spelling).</li>
 *   <li>Letters: 1..{@value #MAX_PARAGRAPHS} paragraphs of at most {@value #MAX_PARAGRAPH_CHARS} characters,
 *       at most {@value #MAX_WORDS} words; header, company, job title, date, salutation, closing and
 *       signature are read-only (compared with the base version).</li>
 *   <li>Changed free text is re-checked with the {@link FactCheck} rules; findings on owner text are
 *       WARNING, never BLOCKING.</li>
 * </ul>
 * Field paths are prefixed {@code content.}; finding paths are relative to the content (as for AI versions).
 */
public final class EditValidator {

    public static final int MAX_BULLET = 400;
    public static final int MAX_HEADLINE = TailoredCvAssembler.MAX_HEADLINE;
    public static final int MAX_SUMMARY = TailoredCvAssembler.MAX_SUMMARY;
    public static final int MAX_PARAGRAPHS = 8;
    public static final int MAX_PARAGRAPH_CHARS = 1500;
    public static final int MAX_WORDS = 600;

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final String PREFIX = "content.";

    /** A request field error (the caller turns these into a 400 {@code VALIDATION_FAILED}). */
    public record FieldError(String field, String message) {
    }

    /**
     * The outcome: field errors (then {@code content} is null), or the normalized content with its findings.
     */
    public record Result<T>(T content, List<Finding> findings, List<FieldError> errors) {

        public Result {
            findings = findings == null ? List.of() : List.copyOf(findings);
            errors = errors == null ? List.of() : List.copyOf(errors);
        }

        public boolean valid() {
            return errors.isEmpty();
        }
    }

    private final ProfileFacts facts;
    private final int profileVersion;
    private final FactCheck check;
    private final List<FieldError> errors = new ArrayList<>();
    private final List<Finding> findings = new ArrayList<>();

    private EditValidator(ProfileFacts facts, int profileVersion, FactCheck check) {
        this.facts = facts;
        this.profileVersion = profileVersion;
        this.check = check == null ? FactCheck.unavailable() : check;
    }

    // ------------------------------------------------------------------ CV

    /**
     * @param edited         the owner's content
     * @param facts          the base version's profile version
     * @param profileVersion its number (for messages)
     */
    public static Result<TailoredCvContent> validateCv(TailoredCvContent edited, ProfileFacts facts, int profileVersion,
                                                       FactCheck check) {
        EditValidator v = new EditValidator(facts, profileVersion, check);
        if (edited == null) {
            v.error("content", "content is required.");
            return v.result(null);
        }
        v.header(edited.header());
        String headline = v.freeText("headline", edited.headline(), facts.profile().headline(), MAX_HEADLINE,
                facts.statedNumbers());
        String summary = v.freeText("summary", edited.summary(), facts.profile().summary(), MAX_SUMMARY,
                facts.summaryNumbers());
        List<String> skills = v.skills(edited.skills());
        List<ExperienceItem> experience = v.experience(edited.experience());
        List<ProjectItem> projects = v.projects(edited.projects());
        List<CertificationItem> certifications = v.certifications(edited.certifications());
        List<EducationItem> education = v.education(edited.education());
        List<LanguageItem> languages = v.languages(edited.languages());
        if (!v.errors.isEmpty()) {
            return v.result(null);
        }
        return v.result(new TailoredCvContent(facts.header(), headline, summary, skills, experience, projects,
                certifications, education, languages));
    }

    private void header(Header edited) {
        Header profile = facts.header();
        Header h = edited == null ? new Header(null, null, null, null, List.of()) : edited;
        readOnly("header.fullName", h.fullName(), profile.fullName());
        readOnly("header.email", h.email(), profile.email());
        readOnly("header.phone", h.phone(), profile.phone());
        readOnly("header.location", h.location(), profile.location());
        if (!sameLinks(h.links(), profile.links())) {
            error("header.links", readOnlyMessage("header.links"));
        }
    }

    private String freeText(String field, String edited, String original, int max, Set<String> numbers) {
        String t = norm(edited);
        if (t == null) {
            return null;
        }
        if (t.length() > max) {
            error(field, PREFIX + field + " must be at most " + max + " characters.");
            return t;
        }
        if (Objects.equals(t, norm(original))) {
            return original;
        }
        checkText(field, t, numbers, facts.skillsGlobal(), "your profile");
        return t;
    }

    private List<String> skills(List<String> edited) {
        Set<String> out = new LinkedHashSet<>();
        Set<String> seen = new HashSet<>();
        List<String> list = nz(edited);
        for (int i = 0; i < list.size(); i++) {
            String name = norm(list.get(i));
            var profileName = facts.profileSkill(name, check);
            if (profileName.isEmpty()) {
                error("skills[" + i + "]", PREFIX + "skills[" + i + "]: '" + (name == null ? "" : name)
                        + "' is not in your profile skills. Add it to your profile first.");
                continue;
            }
            if (seen.add(profileName.get().toLowerCase(Locale.ROOT))) {
                out.add(profileName.get());
            }
        }
        return List.copyOf(out);
    }

    private List<ExperienceItem> experience(List<ExperienceItem> edited) {
        List<ExperienceItem> list = nz(edited);
        List<String> refs = new ArrayList<>();
        for (ExperienceItem e : list) {
            refs.add(e == null ? null : TailoredCvAssembler.ref(e.ref()));
        }
        if (!refs.equals(facts.experienceRefs())) {
            error("experience", PREFIX + "experience: every role from your profile stays in the CV, in profile order ("
                    + String.join(", ", facts.experienceRefs()) + ").");
            return List.of();
        }
        List<ExperienceItem> out = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            ExperienceItem e = list.get(i);
            Experience p = facts.experience().get(i);
            String ref = refs.get(i);
            String path = "experience[" + i + "]";
            readOnly(path + ".title", e.title(), p.title());
            readOnly(path + ".company", e.company(), p.company());
            readOnly(path + ".location", e.location(), p.location());
            readOnly(path + ".startDate", e.startDate(), p.startDate());
            readOnly(path + ".endDate", e.endDate(), p.endDate());
            if (Boolean.TRUE.equals(e.current()) != Boolean.TRUE.equals(p.current())) {
                error(path + ".current", readOnlyMessage(path + ".current"));
            }
            if (!sameList(e.technologies(), p.technologies())) {
                error(path + ".technologies", readOnlyMessage(path + ".technologies"));
            }
            List<Bullet> bullets = bullets(path, ref, e.highlights(), "role");
            out.add(new ExperienceItem(ref, p.title(), p.company(), p.location(), p.startDate(), p.endDate(),
                    p.current(), p.technologies(), bullets));
        }
        return out;
    }

    private List<ProjectItem> projects(List<ProjectItem> edited) {
        List<ProjectItem> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        List<ProjectItem> list = nz(edited);
        for (int i = 0; i < list.size(); i++) {
            ProjectItem e = list.get(i);
            String path = "projects[" + i + "]";
            String ref = e == null ? null : TailoredCvAssembler.ref(e.ref());
            if (!knownRef(path, ref, "P", seen)) {
                continue;
            }
            Project p = facts.projects().get(facts.indexOf(ref));
            readOnly(path + ".name", e.name(), p.name());
            readOnly(path + ".description", e.description(), p.description());
            readOnly(path + ".url", e.url(), p.url());
            if (!sameList(e.technologies(), p.technologies())) {
                error(path + ".technologies", readOnlyMessage(path + ".technologies"));
            }
            List<Bullet> bullets = bullets(path, ref, e.highlights(), "project");
            out.add(new ProjectItem(ref, p.name(), p.description(), p.technologies(), p.url(), bullets));
        }
        return out;
    }

    private List<CertificationItem> certifications(List<CertificationItem> edited) {
        List<CertificationItem> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        List<CertificationItem> list = nz(edited);
        for (int i = 0; i < list.size(); i++) {
            CertificationItem e = list.get(i);
            String path = "certifications[" + i + "]";
            String ref = e == null ? null : TailoredCvAssembler.ref(e.ref());
            if (!knownRef(path, ref, "C", seen)) {
                continue;
            }
            Certification c = facts.certifications().get(facts.indexOf(ref));
            readOnly(path + ".name", e.name(), c.name());
            readOnly(path + ".issuer", e.issuer(), c.issuer());
            readOnly(path + ".issued", e.issued(), c.issued());
            readOnly(path + ".expires", e.expires(), c.expires());
            out.add(new CertificationItem(ref, c.name(), c.issuer(), c.issued(), c.expires()));
        }
        return out;
    }

    private List<EducationItem> education(List<EducationItem> edited) {
        List<EducationItem> list = nz(edited);
        List<String> refs = new ArrayList<>();
        for (EducationItem e : list) {
            refs.add(e == null ? null : TailoredCvAssembler.ref(e.ref()));
        }
        if (!refs.equals(facts.educationRefs())) {
            error("education", PREFIX + "education: every education entry from your profile stays in the CV.");
            return List.of();
        }
        List<EducationItem> out = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            EducationItem e = list.get(i);
            Education d = facts.education().get(i);
            String path = "education[" + i + "]";
            readOnly(path + ".institution", e.institution(), d.institution());
            readOnly(path + ".qualification", e.qualification(), d.qualification());
            readOnly(path + ".field", e.field(), d.field());
            readOnly(path + ".startDate", e.startDate(), d.startDate());
            readOnly(path + ".endDate", e.endDate(), d.endDate());
            out.add(new EducationItem(refs.get(i), d.institution(), d.qualification(), d.field(), d.startDate(),
                    d.endDate()));
        }
        return out;
    }

    private List<LanguageItem> languages(List<LanguageItem> edited) {
        List<Language> profile = facts.languages();
        List<LanguageItem> list = nz(edited);
        boolean same = list.size() == profile.size();
        for (int i = 0; same && i < list.size(); i++) {
            LanguageItem e = list.get(i);
            same = e != null && Objects.equals(norm(e.name()), norm(profile.get(i).name()))
                    && Objects.equals(norm(e.level()), norm(profile.get(i).level()));
        }
        if (!same) {
            error("languages", readOnlyMessage("languages"));
        }
        List<LanguageItem> out = new ArrayList<>();
        for (Language l : profile) {
            out.add(new LanguageItem(l.name(), l.level()));
        }
        return out;
    }

    /** True for a ref of this profile version with the prefix, seen for the first time; else a field error. */
    private boolean knownRef(String path, String ref, String prefix, Set<String> seen) {
        if (ref == null) {
            error(path + ".ref", PREFIX + path + ".ref is required.");
            return false;
        }
        if (!ref.startsWith(prefix) || facts.indexOf(ref) < 0) {
            error(path + ".ref", PREFIX + path + ".ref '" + ref + "' is not in profile version " + profileVersion + ".");
            return false;
        }
        if (!seen.add(ref)) {
            error(path + ".ref", PREFIX + path + ".ref '" + ref + "' is listed twice.");
            return false;
        }
        return true;
    }

    private List<Bullet> bullets(String path, String parentRef, List<Bullet> edited, String parentKind) {
        List<Bullet> out = new ArrayList<>();
        List<Bullet> list = nz(edited);
        for (int j = 0; j < list.size(); j++) {
            Bullet b = list.get(j);
            String bp = path + ".highlights[" + j + "]";
            String ref = b == null ? null : TailoredCvAssembler.ref(b.ref());
            ProfileFacts.Highlight fact = facts.highlight(ref);
            if (ref == null) {
                error(bp + ".ref", PREFIX + bp + ".ref is required: every point refers to a point of this "
                        + parentKind + " in your profile (for example " + parentRef + ".H1).");
                continue;
            }
            if (fact == null) {
                error(bp + ".ref", PREFIX + bp + ".ref '" + ref + "' is not in profile version " + profileVersion + ".");
                continue;
            }
            if (!fact.parentRef().equals(parentRef)) {
                error(bp + ".ref", PREFIX + bp + ".ref '" + ref + "' belongs to another " + parentKind + ".");
                continue;
            }
            String text = norm(b.text());
            if (text == null || text.length() > MAX_BULLET) {
                error(bp + ".text", PREFIX + bp + ".text must be 1 to " + MAX_BULLET + " characters.");
                continue;
            }
            if (Objects.equals(text, norm(fact.text()))) {
                out.add(new Bullet(ref, fact.text()));
                continue;
            }
            checkText(bp, text, facts.numbers(ref), facts.skills(ref), "this point of your profile");
            out.add(new Bullet(ref, text));
        }
        return out;
    }

    /** FactCheck on owner text: WARNING findings only. */
    private void checkText(String path, String text, Set<String> numbers, Set<UUID> skills, String where) {
        for (FactCheck.Problem p : check.check(text, numbers, skills, null)) {
            String message = switch (p.code()) {
                case CONTACT_DETAILS -> "Contact details belong in your profile's header, not in this text.";
                case SKILL_NOT_IN_PROFILE -> FactCheck.UNCHECKED_SKILLS.equals(p.value())
                        ? "Skills in this text couldn't be checked (the skill list is unavailable). Check them."
                        : "'" + p.value() + "' is not in " + where + ". Check it before approving.";
                default -> "The number " + p.value() + " is not in " + where + ". Check it before approving.";
            };
            findings.add(Finding.warning(p.code(), path, p.value(), message));
        }
    }

    // ------------------------------------------------------------------ cover letter

    /**
     * @param edited the owner's content
     * @param base   the version it is based on (the source of the read-only fields)
     */
    public static Result<CoverLetterContent> validateCoverLetter(CoverLetterContent edited, CoverLetterContent base,
                                                                 ProfileFacts facts, int profileVersion,
                                                                 FactCheck check) {
        EditValidator v = new EditValidator(facts, profileVersion, check);
        if (edited == null || base == null) {
            v.error("content", "content is required.");
            return v.result(null);
        }
        v.header(edited.header());
        v.fixed("company", edited.company(), base.company());
        v.fixed("jobTitle", edited.jobTitle(), base.jobTitle());
        if (!Objects.equals(edited.date(), base.date())) {
            v.error("date", fixedMessage("date"));
        }
        v.fixed("salutation", edited.salutation(), base.salutation());
        v.fixed("closing", edited.closing(), base.closing());
        v.readOnly("signature", edited.signature(), base.signature());

        List<String> paragraphs = new ArrayList<>();
        boolean ok = !edited.paragraphs().isEmpty() && edited.paragraphs().size() <= MAX_PARAGRAPHS;
        for (String raw : edited.paragraphs()) {
            String p = CoverLetterAssembler.clean(raw);
            if (p.isEmpty() || p.length() > MAX_PARAGRAPH_CHARS) {
                ok = false;
            }
            paragraphs.add(p);
        }
        if (!ok || CoverLetterAssembler.words(paragraphs) > MAX_WORDS) {
            v.error("paragraphs", PREFIX + "paragraphs must have 1 to " + MAX_PARAGRAPHS + " paragraphs of at most "
                    + MAX_PARAGRAPH_CHARS + " characters, " + MAX_WORDS + " words in total.");
        }
        if (!v.errors.isEmpty()) {
            return v.result(null);
        }
        CoverLetterAssembler.Checked checked = CoverLetterAssembler.checkParagraphs(paragraphs, facts,
                base.jobTitle(), base.company(), null, v.check, Severity.WARNING);
        v.findings.addAll(checked.findings());
        return v.result(new CoverLetterContent(facts.header(), base.company(), base.jobTitle(), base.date(),
                base.salutation(), paragraphs, base.closing(), base.signature()));
    }

    // ------------------------------------------------------------------ helpers

    /** Read-only profile fact: compared after trimming and collapsing whitespace. */
    private void readOnly(String field, String edited, String profile) {
        if (!Objects.equals(norm(edited), norm(profile))) {
            error(field, readOnlyMessage(field));
        }
    }

    /** Read-only field set by TalentMatch (not a profile fact). */
    private void fixed(String field, String edited, String base) {
        if (!Objects.equals(norm(edited), norm(base))) {
            error(field, fixedMessage(field));
        }
    }

    private String readOnlyMessage(String field) {
        return PREFIX + field + " can't be changed here: it comes from your profile (version " + profileVersion
                + "). Change it with PUT /api/profile, then generate again.";
    }

    private static String fixedMessage(String field) {
        return PREFIX + field + " can't be changed here: it is set by TalentMatch for this application.";
    }

    private void error(String field, String message) {
        errors.add(new FieldError(field.startsWith(PREFIX) || field.equals("content") ? field : PREFIX + field,
                message));
    }

    private <T> Result<T> result(T content) {
        return errors.isEmpty() ? new Result<>(content, findings, List.of()) : new Result<>(null, List.of(), errors);
    }

    private static boolean sameLinks(List<LinkItem> a, List<LinkItem> b) {
        List<LinkItem> x = nz(a);
        List<LinkItem> y = nz(b);
        if (x.size() != y.size()) {
            return false;
        }
        for (int i = 0; i < x.size(); i++) {
            LinkItem l = x.get(i);
            LinkItem r = y.get(i);
            if (l == null || r == null || !Objects.equals(norm(l.label()), norm(r.label()))
                    || !Objects.equals(norm(l.url()), norm(r.url()))) {
                return false;
            }
        }
        return true;
    }

    private static boolean sameList(List<String> a, List<String> b) {
        List<String> x = nz(a);
        List<String> y = nz(b);
        if (x.size() != y.size()) {
            return false;
        }
        for (int i = 0; i < x.size(); i++) {
            if (!Objects.equals(norm(x.get(i)), norm(y.get(i)))) {
                return false;
            }
        }
        return true;
    }

    /** Trimmed with whitespace collapsed; null for null or blank. */
    static String norm(String s) {
        if (s == null) {
            return null;
        }
        String t = WHITESPACE.matcher(s).replaceAll(" ").strip();
        return t.isEmpty() ? null : t;
    }

    private static <T> List<T> nz(List<T> list) {
        return list == null ? List.of() : list;
    }
}
