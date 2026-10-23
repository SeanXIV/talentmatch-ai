package com.talentmatch.application.tailoring;

import static com.talentmatch.application.tailoring.TailoringFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

import com.talentmatch.application.content.Bullet;
import com.talentmatch.application.content.CertificationItem;
import com.talentmatch.application.content.CoverLetterContent;
import com.talentmatch.application.content.EducationItem;
import com.talentmatch.application.content.ExperienceItem;
import com.talentmatch.application.content.Finding;
import com.talentmatch.application.content.FindingCode;
import com.talentmatch.application.content.Header;
import com.talentmatch.application.content.LanguageItem;
import com.talentmatch.application.content.LinkItem;
import com.talentmatch.application.content.ProjectItem;
import com.talentmatch.application.content.Severity;
import com.talentmatch.application.content.TailoredCvContent;
import com.talentmatch.application.tailoring.EditValidator.FieldError;
import com.talentmatch.application.tailoring.EditValidator.Result;
import com.talentmatch.application.tailoring.TailoredCvDraft.Entry;
import com.talentmatch.application.tailoring.TailoredCvDraft.Highlight;
import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;

/** §9.1.8: read-only fields, structure rules, refs, skills, limits and WARNING-only findings (decision i). */
class EditValidatorTest {

    private static final int VERSION = 4;

    /** An AI CV version built from the fixture profile: E1 (3 points), E2 (2), P1, C1, D1, 3 skills. */
    private static TailoredCvContent base() {
        TailoredCvDraft draft = new TailoredCvDraft(null, null, List.of("Java", "PostgreSQL", "Docker"),
                List.of(new Entry("E1", List.of(new Highlight("E1.H1", E1H1), new Highlight("E1.H2", E1H2),
                                new Highlight("E1.H3", E1H3))),
                        new Entry("E2", List.of(new Highlight("E2.H1", E2H1), new Highlight("E2.H2", E2H2)))),
                List.of(new Entry("P1", List.of(new Highlight("P1.H1", P1H1)))), List.of("C1"));
        var r = TailoredCvAssembler.assemble(draft, facts(), job(), check());
        return ((AssemblyResult.Assembled<TailoredCvContent>) r).content();
    }

    private static Result<TailoredCvContent> validate(TailoredCvContent edited) {
        return EditValidator.validateCv(edited, facts(), VERSION, check());
    }

    private static String readOnly(String field) {
        return "content." + field + " can't be changed here: it comes from your profile (version 4). Change it with "
                + "PUT /api/profile, then generate again.";
    }

    private static String setByTalentMatch(String field) {
        return "content." + field + " can't be changed here: it is set by TalentMatch for this application.";
    }

    private static TailoredCvContent with(TailoredCvContent c, UnaryOperator<Builder> change) {
        return change.apply(new Builder(c)).build();
    }

    /** A copy of a CV content with fields replaced. */
    private static final class Builder {
        Header header;
        String headline;
        String summary;
        List<String> skills;
        List<ExperienceItem> experience;
        List<ProjectItem> projects;
        List<CertificationItem> certifications;
        List<EducationItem> education;
        List<LanguageItem> languages;

        Builder(TailoredCvContent c) {
            header = c.header();
            headline = c.headline();
            summary = c.summary();
            skills = new ArrayList<>(c.skills());
            experience = new ArrayList<>(c.experience());
            projects = new ArrayList<>(c.projects());
            certifications = new ArrayList<>(c.certifications());
            education = new ArrayList<>(c.education());
            languages = new ArrayList<>(c.languages());
        }

        Builder role(int i, UnaryOperator<ExperienceItem> change) {
            experience.set(i, change.apply(experience.get(i)));
            return this;
        }

        TailoredCvContent build() {
            return new TailoredCvContent(header, headline, summary, skills, experience, projects, certifications,
                    education, languages);
        }
    }

    private static ExperienceItem title(ExperienceItem e, String title) {
        return new ExperienceItem(e.ref(), title, e.company(), e.location(), e.startDate(), e.endDate(), e.current(),
                e.technologies(), e.highlights());
    }

    private static ExperienceItem bullets(ExperienceItem e, Bullet... bullets) {
        return new ExperienceItem(e.ref(), e.title(), e.company(), e.location(), e.startDate(), e.endDate(),
                e.current(), e.technologies(), List.of(bullets));
    }

    private static List<FieldError> errors(TailoredCvContent edited) {
        Result<TailoredCvContent> r = validate(edited);
        assertThat(r.valid()).isFalse();
        assertThat(r.content()).isNull();
        assertThat(r.findings()).isEmpty();
        return r.errors();
    }

    // ------------------------------------------------------------------ CV

    @Test
    void anUnchangedVersionIsValidWithoutFindings() {
        Result<TailoredCvContent> r = validate(base());
        assertThat(r.valid()).isTrue();
        assertThat(r.errors()).isEmpty();
        assertThat(r.findings()).isEmpty();
        assertThat(r.content()).isEqualTo(base());
    }

    @Test
    void whitespaceOnlyDifferencesInFactFieldsAreAccepted() {
        TailoredCvContent edited = with(base(), b -> b.role(0, e -> title(e, "  Senior   Backend Engineer ")));
        Result<TailoredCvContent> r = validate(edited);
        assertThat(r.valid()).isTrue();
        assertThat(r.content().experience().get(0).title()).as("the profile's value is stored")
                .isEqualTo("Senior Backend Engineer");
    }

    @Test
    void ownerEditsAreKeptAndReorderedPointsAndDroppedProjectsAreFine() {
        TailoredCvContent edited = with(base(), b -> {
            b.headline = "Java backend engineer";
            b.summary = "Backend engineer with 10 years of experience.";
            b.projects.clear();
            b.certifications.clear();
            b.skills.remove("Docker");
            return b.role(0, e -> bullets(e, new Bullet("E1.H3", "Moved billing onto Kafka event streams"),
                    new Bullet("e1.h1", E1H1)));
        });
        Result<TailoredCvContent> r = validate(edited);
        assertThat(r.valid()).isTrue();
        assertThat(r.findings()).isEmpty();
        TailoredCvContent c = r.content();
        assertThat(c.headline()).isEqualTo("Java backend engineer");
        assertThat(c.summary()).isEqualTo("Backend engineer with 10 years of experience.");
        assertThat(c.projects()).isEmpty();
        assertThat(c.skills()).containsExactly("Java", "PostgreSQL");
        assertThat(c.experience().get(0).highlights()).containsExactly(
                new Bullet("E1.H3", "Moved billing onto Kafka event streams"), new Bullet("E1.H1", E1H1));
    }

    @Test
    void factFieldsAreReadOnly() {
        TailoredCvContent b = base();
        ExperienceItem e = b.experience().get(0);
        assertThat(errors(with(b, x -> x.role(0, r -> title(r, "CTO"))))).containsExactly(
                new FieldError("content.experience[0].title", readOnly("experience[0].title")));
        assertThat(errors(with(b, x -> x.role(1, r -> new ExperienceItem(r.ref(), r.title(), "Globex Corp",
                r.location(), "2015-01", r.endDate(), true, List.of("Python"), r.highlights())))))
                .extracting(FieldError::field).containsExactly("content.experience[1].company",
                        "content.experience[1].startDate", "content.experience[1].current",
                        "content.experience[1].technologies");
        assertThat(errors(with(b, x -> x.role(0, r -> new ExperienceItem(r.ref(), r.title(), r.company(), "Durban",
                r.startDate(), "2026-01", r.current(), r.technologies(), r.highlights())))))
                .extracting(FieldError::field).containsExactly("content.experience[0].location",
                        "content.experience[0].endDate");
        assertThat(e.title()).isEqualTo("Senior Backend Engineer");
    }

    @Test
    void theHeaderIsReadOnly() {
        TailoredCvContent b = base();
        Header h = b.header();
        assertThat(errors(with(b, x -> {
            x.header = new Header("Zoe Ndlovu", "other@example.com", h.phone(), h.location(), h.links());
            return x;
        }))).containsExactly(new FieldError("content.header.fullName", readOnly("header.fullName")),
                new FieldError("content.header.email", readOnly("header.email")));
        assertThat(errors(with(b, x -> {
            x.header = new Header(h.fullName(), h.email(), h.phone(), h.location(),
                    List.of(new LinkItem("GitHub", "https://github.com/other")));
            return x;
        }))).containsExactly(new FieldError("content.header.links", readOnly("header.links")));
        assertThat(errors(with(b, x -> {
            x.header = null;
            return x;
        }))).extracting(FieldError::field).contains("content.header.fullName");
    }

    @Test
    void projectCertificationEducationAndLanguageFieldsAreReadOnly() {
        TailoredCvContent b = base();
        ProjectItem p = b.projects().get(0);
        assertThat(errors(with(b, x -> {
            x.projects.set(0, new ProjectItem("P1", "Ledger 2", "Other", List.of("Rust"), "https://x.example",
                    p.highlights()));
            return x;
        }))).extracting(FieldError::field).containsExactly("content.projects[0].name",
                "content.projects[0].description", "content.projects[0].url", "content.projects[0].technologies");
        assertThat(errors(with(b, x -> {
            x.certifications.set(0, new CertificationItem("C1", "AWS Certified Architect", "Amazon", "2022-05",
                    null));
            return x;
        }))).containsExactly(new FieldError("content.certifications[0].name", readOnly("certifications[0].name")),
                new FieldError("content.certifications[0].expires", readOnly("certifications[0].expires")));
        assertThat(errors(with(b, x -> {
            x.education.set(0, new EducationItem("D1", "University of Cape Town", "BSc", "Computer Science", "2012",
                    "2016"));
            return x;
        }))).extracting(FieldError::field).containsExactly("content.education[0].institution",
                "content.education[0].endDate");
        assertThat(errors(with(b, x -> {
            x.languages.set(1, new LanguageItem("isiXhosa", "Fluent"));
            return x;
        }))).containsExactly(new FieldError("content.languages", readOnly("languages")));
        assertThat(errors(with(b, x -> {
            x.languages.remove(1);
            return x;
        }))).extracting(FieldError::field).containsExactly("content.languages");
    }

    @Test
    void rolesCantBeRemovedOrReordered() {
        String message = "content.experience: every role from your profile stays in the CV, in profile order (E1, E2).";
        assertThat(errors(with(base(), x -> {
            x.experience.remove(1);
            return x;
        }))).containsExactly(new FieldError("content.experience", message));
        assertThat(errors(with(base(), x -> {
            java.util.Collections.reverse(x.experience);
            return x;
        }))).containsExactly(new FieldError("content.experience", message));
        assertThat(errors(with(base(), x -> {
            x.experience.clear();
            return x;
        }))).containsExactly(new FieldError("content.experience", message));
    }

    @Test
    void educationCantBeRemoved() {
        assertThat(errors(with(base(), x -> {
            x.education.clear();
            return x;
        }))).containsExactly(new FieldError("content.education",
                "content.education: every education entry from your profile stays in the CV."));
    }

    @Test
    void refsMustExistInTheProfileVersionAndBelongToTheirEntry() {
        TailoredCvContent b = base();
        ProjectItem p = b.projects().get(0);
        assertThat(errors(with(b, x -> {
            x.projects.add(new ProjectItem("P9", "Ghost", null, List.of(), null, List.of()));
            return x;
        }))).containsExactly(new FieldError("content.projects[1].ref",
                "content.projects[1].ref 'P9' is not in profile version 4."));
        assertThat(errors(with(b, x -> {
            x.projects.add(p);
            return x;
        }))).containsExactly(new FieldError("content.projects[1].ref", "content.projects[1].ref 'P1' is listed twice."));
        assertThat(errors(with(b, x -> {
            x.certifications.set(0, new CertificationItem("E1", "AWS Certified Developer", "Amazon", "2022-05",
                    "2025-05"));
            return x;
        }))).containsExactly(new FieldError("content.certifications[0].ref",
                "content.certifications[0].ref 'E1' is not in profile version 4."));
        assertThat(errors(with(b, x -> x.role(0, e -> bullets(e, new Bullet("E1.H1", E1H1),
                new Bullet("E1.H1", E1H1), new Bullet("E2.H1", E2H1)))))).containsExactly(
                new FieldError("content.experience[0].highlights[2].ref",
                        "content.experience[0].highlights[2].ref 'E2.H1' belongs to another role."));
        assertThat(errors(with(b, x -> x.role(0, e -> bullets(e, new Bullet("E1.H9", "made up")))))).containsExactly(
                new FieldError("content.experience[0].highlights[0].ref",
                        "content.experience[0].highlights[0].ref 'E1.H9' is not in profile version 4."));
        assertThat(errors(with(b, x -> x.role(0, e -> bullets(e, new Bullet(null, "text"))))))
                .extracting(FieldError::field).containsExactly("content.experience[0].highlights[0].ref");
    }

    @Test
    void skillsMustBeProfileSkills() {
        assertThat(errors(with(base(), x -> {
            x.skills.add("Kubernetes");
            return x;
        }))).containsExactly(new FieldError("content.skills[3]",
                "content.skills[3]: 'Kubernetes' is not in your profile skills. Add it to your profile first."));
        Result<TailoredCvContent> r = validate(with(base(), x -> {
            x.skills.clear();
            x.skills.addAll(List.of("postgres", "JAVA", "java"));
            return x;
        }));
        assertThat(r.content().skills()).as("the profile's spelling, duplicates removed")
                .containsExactly("PostgreSQL", "Java");
    }

    @Test
    void lengthLimits() {
        assertThat(errors(with(base(), x -> x.role(0, e -> bullets(e, new Bullet("E1.H1", "x".repeat(401)))))))
                .containsExactly(new FieldError("content.experience[0].highlights[0].text",
                        "content.experience[0].highlights[0].text must be 1 to 400 characters."));
        assertThat(errors(with(base(), x -> x.role(0, e -> bullets(e, new Bullet("E1.H1", "   ")))))).containsExactly(
                new FieldError("content.experience[0].highlights[0].text",
                        "content.experience[0].highlights[0].text must be 1 to 400 characters."));
        assertThat(validate(with(base(), x -> x.role(0, e -> bullets(e, new Bullet("E1.H1",
                "Cut API latency " + "a".repeat(384)))))).valid()).as("exactly 400").isTrue();
        assertThat(errors(with(base(), x -> {
            x.summary = "s".repeat(901);
            return x;
        }))).containsExactly(new FieldError("content.summary", "content.summary must be at most 900 characters."));
        assertThat(errors(with(base(), x -> {
            x.headline = "h".repeat(121);
            return x;
        }))).containsExactly(new FieldError("content.headline", "content.headline must be at most 120 characters."));
    }

    @Test
    void changedFreeTextGetsWarningFindingsOnly() {
        Result<TailoredCvContent> r = validate(with(base(), x -> {
            x.summary = "Backend engineer with 13 years of Kubernetes.";
            return x.role(0, e -> bullets(e, new Bullet("E1.H2", "Led a team of nine engineers, mail zoe@example.com"),
                    new Bullet("E1.H1", "Cut API latency by 40% for 12,000 daily users with Kafka")));
        }));
        assertThat(r.valid()).isTrue();
        assertThat(r.findings()).extracting(Finding::severity).containsOnly(Severity.WARNING);
        assertThat(r.findings()).extracting(Finding::code, Finding::path, Finding::value).containsExactly(
                tuple(FindingCode.NUMBER_NOT_IN_PROFILE, "summary", "13"),
                tuple(FindingCode.SKILL_NOT_IN_PROFILE, "summary", "Kubernetes"),
                tuple(FindingCode.CONTACT_DETAILS, "experience[0].highlights[0]", "zoe@example.com"),
                tuple(FindingCode.NUMBER_NOT_IN_PROFILE, "experience[0].highlights[0]", "nine"));
        assertThat(r.content().summary()).as("owner text is kept").isEqualTo("Backend engineer with 13 years of Kubernetes.");
        assertThat(r.findings().get(0).message()).isEqualTo("The number 13 is not in your profile. Check it before "
                + "approving.");
    }

    @Test
    void nullContentIsAnError() {
        assertThat(EditValidator.validateCv(null, facts(), VERSION, check()).errors())
                .containsExactly(new FieldError("content", "content is required."));
    }

    // ------------------------------------------------------------------ cover letter

    private static CoverLetterContent letterBase() {
        var r = CoverLetterAssembler.assemble(new CoverLetterDraft(List.of(CoverLetterAssemblerTest.G0,
                CoverLetterAssemblerTest.G1, CoverLetterAssemblerTest.G2)), facts(), job(), check(),
                new CoverLetterAssembler.Options(TODAY, false, null, 20, 100));
        return ((AssemblyResult.Assembled<CoverLetterContent>) r).content();
    }

    private static CoverLetterContent letter(CoverLetterContent b, String company, String jobTitle,
                                             java.time.LocalDate date, String salutation, List<String> paragraphs,
                                             String closing, String signature) {
        return new CoverLetterContent(b.header(), company, jobTitle, date, salutation, paragraphs, closing, signature);
    }

    private static Result<CoverLetterContent> validateLetter(CoverLetterContent edited) {
        return EditValidator.validateCoverLetter(edited, letterBase(), facts(), VERSION, check());
    }

    @Test
    void anUnchangedLetterIsValid() {
        Result<CoverLetterContent> r = validateLetter(letterBase());
        assertThat(r.valid()).isTrue();
        assertThat(r.findings()).isEmpty();
        assertThat(r.content()).isEqualTo(letterBase());
    }

    @Test
    void serverSetLetterFieldsAreReadOnly() {
        CoverLetterContent b = letterBase();
        Result<CoverLetterContent> r = validateLetter(letter(b, "Initrode", "CTO", TODAY.plusDays(1), "Hi there,",
                b.paragraphs(), "Cheers,", "Zoe"));
        assertThat(r.valid()).isFalse();
        assertThat(r.errors()).containsExactly(
                new FieldError("content.company", setByTalentMatch("company")),
                new FieldError("content.jobTitle", setByTalentMatch("jobTitle")),
                new FieldError("content.date", setByTalentMatch("date")),
                new FieldError("content.salutation", setByTalentMatch("salutation")),
                new FieldError("content.closing", setByTalentMatch("closing")),
                new FieldError("content.signature", readOnly("signature")));
    }

    @Test
    void theLetterHeaderComesFromTheProfile() {
        CoverLetterContent b = letterBase();
        Header h = b.header();
        CoverLetterContent edited = new CoverLetterContent(new Header(h.fullName(), h.email(), "011 000 0000",
                h.location(), h.links()), b.company(), b.jobTitle(), b.date(), b.salutation(), b.paragraphs(),
                b.closing(), b.signature());
        assertThat(validateLetter(edited).errors())
                .containsExactly(new FieldError("content.header.phone", readOnly("header.phone")));
    }

    @Test
    void paragraphLimits() {
        CoverLetterContent b = letterBase();
        String message = "content.paragraphs must have 1 to 8 paragraphs of at most 1500 characters, 600 words in total.";
        for (List<String> bad : List.of(List.<String>of(), java.util.Collections.nCopies(9, "Short paragraph."),
                List.of("x".repeat(1501)), List.of("Fine.", " "), List.of("word ".repeat(601).strip()))) {
            Result<CoverLetterContent> r = validateLetter(letter(b, b.company(), b.jobTitle(), b.date(), b.salutation(),
                    bad, b.closing(), b.signature()));
            assertThat(r.errors()).as("%s paragraphs", bad.size())
                    .containsExactly(new FieldError("content.paragraphs", message));
        }
        Result<CoverLetterContent> eight = validateLetter(letter(b, b.company(), b.jobTitle(), b.date(),
                b.salutation(), java.util.Collections.nCopies(8, "Short paragraph."), b.closing(), b.signature()));
        assertThat(eight.valid()).isTrue();
        assertThat(eight.content().paragraphs()).hasSize(8);
    }

    @Test
    void letterFindingsAreWarningsNotBlocking() {
        CoverLetterContent b = letterBase();
        List<String> paragraphs = List.of(CoverLetterAssemblerTest.G0, CoverLetterAssemblerTest.BAD,
                CoverLetterAssemblerTest.BAD2, "  Extra   spaces here.  ");
        Result<CoverLetterContent> r = validateLetter(letter(b, b.company(), b.jobTitle(), b.date(), b.salutation(),
                paragraphs, b.closing(), b.signature()));
        assertThat(r.valid()).as("owner edits are never rejected for findings").isTrue();
        assertThat(r.findings()).isNotEmpty().extracting(Finding::severity).containsOnly(Severity.WARNING);
        assertThat(r.findings()).extracting(Finding::code).contains(FindingCode.CONTACT_DETAILS,
                FindingCode.NUMBER_NOT_IN_PROFILE, FindingCode.YEAR_NOT_IN_PROFILE, FindingCode.SKILL_NOT_IN_PROFILE);
        assertThat(r.content().paragraphs()).last().isEqualTo("Extra spaces here.");
    }

    @Test
    void theFixedNoticeSentenceIsNotChecked() {
        CoverLetterContent b = letterBase();
        List<String> paragraphs = new ArrayList<>(b.paragraphs());
        paragraphs.add(CoverLetterAssembler.noticeSentence(45));
        Result<CoverLetterContent> r = validateLetter(letter(b, b.company(), b.jobTitle(), b.date(), b.salutation(),
                paragraphs, b.closing(), b.signature()));
        assertThat(r.findings()).isEmpty();
    }

    @Test
    void nullLetterOrBaseIsAnError() {
        assertThat(EditValidator.validateCoverLetter(null, letterBase(), facts(), VERSION, check()).errors())
                .containsExactly(new FieldError("content", "content is required."));
        assertThat(EditValidator.validateCoverLetter(letterBase(), null, facts(), VERSION, check()).valid()).isFalse();
    }
}
