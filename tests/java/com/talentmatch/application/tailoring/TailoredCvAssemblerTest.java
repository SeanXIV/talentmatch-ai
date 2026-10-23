package com.talentmatch.application.tailoring;

import static com.talentmatch.application.tailoring.TailoringFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;

import com.talentmatch.application.content.Bullet;
import com.talentmatch.application.content.CertificationItem;
import com.talentmatch.application.content.EducationItem;
import com.talentmatch.application.content.ExperienceItem;
import com.talentmatch.application.content.Finding;
import com.talentmatch.application.content.FindingCode;
import com.talentmatch.application.content.LanguageItem;
import com.talentmatch.application.content.Severity;
import com.talentmatch.application.content.TailoredCvContent;
import com.talentmatch.application.tailoring.TailoredCvDraft.Entry;
import com.talentmatch.application.tailoring.TailoredCvDraft.Highlight;
import com.talentmatch.profile.ProfileDocument;
import com.talentmatch.profile.ProfileDocument.Experience;
import com.talentmatch.profile.ProfileDocument.Project;
import com.talentmatch.profile.ProfileDocument.SkillEntry;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/** §9.1.6: selection by reference, repairs, caps, defaults and the rejection rule (decisions e, f, h). */
class TailoredCvAssemblerTest {

    // ------------------------------------------------------------------ helpers

    private static Highlight h(String ref, String text) {
        return new Highlight(ref, text);
    }

    private static Entry entry(String ref, Highlight... highlights) {
        return new Entry(ref, List.of(highlights));
    }

    /** 15 good items: 5 skills, E1 + 3, E2 + 2, P1 + 1, C1; every text is the original wording. */
    private static Draft good() {
        Draft d = new Draft();
        d.skills.addAll(List.of("Java", "PostgreSQL", "Kafka", "Python", "Docker"));
        d.experience.add(entry("E1", h("E1.H1", E1H1), h("E1.H2", E1H2), h("E1.H3", E1H3)));
        d.experience.add(entry("E2", h("E2.H1", E2H1), h("E2.H2", E2H2)));
        d.projects.add(entry("P1", h("P1.H1", P1H1)));
        d.certifications.add("C1");
        return d;
    }

    /** A mutable draft builder. */
    private static final class Draft {
        String headline;
        String summary;
        final List<String> skills = new ArrayList<>();
        final List<Entry> experience = new ArrayList<>();
        final List<Entry> projects = new ArrayList<>();
        final List<String> certifications = new ArrayList<>();

        Draft with(Consumer<Draft> change) {
            change.accept(this);
            return this;
        }

        TailoredCvDraft build() {
            return new TailoredCvDraft(headline, summary, skills, experience, projects, certifications);
        }
    }

    private static AssemblyResult<TailoredCvContent> run(Draft d) {
        return TailoredCvAssembler.assemble(d.build(), facts(), job(), check());
    }

    private static AssemblyResult.Assembled<TailoredCvContent> assembled(AssemblyResult<TailoredCvContent> r) {
        assertThat(r).isInstanceOf(AssemblyResult.Assembled.class);
        return (AssemblyResult.Assembled<TailoredCvContent>) r;
    }

    private static AssemblyResult.Assembled<TailoredCvContent> assembled(Draft d) {
        return assembled(run(d));
    }

    private static List<Finding> findings(AssemblyResult.Assembled<?> a, FindingCode code) {
        return a.findings().stream().filter(f -> f.code() == code).toList();
    }

    private static List<String> refs(List<Bullet> bullets) {
        return bullets.stream().map(Bullet::ref).toList();
    }

    private static List<String> texts(List<Bullet> bullets) {
        return bullets.stream().map(Bullet::text).toList();
    }

    // ------------------------------------------------------------------ the happy path

    @Test
    void aFullyGroundedAnswerHasNoFindingsAndCopiesEveryFact() {
        var a = assembled(good());
        assertThat(a.findings()).isEmpty();
        TailoredCvContent cv = a.content();
        assertThat(cv.header()).isEqualTo(facts().header());
        assertThat(cv.headline()).isEqualTo("Backend engineer");
        assertThat(cv.summary()).isEqualTo("Backend engineer with 7 years of Java and PostgreSQL.");
        assertThat(cv.skills()).containsExactly("Java", "PostgreSQL", "Kafka", "Python", "Docker");
        ExperienceItem e1 = cv.experience().get(0);
        assertThat(e1).isEqualTo(new ExperienceItem("E1", "Senior Backend Engineer", "Acme Ltd", "Cape Town",
                "2021-03", null, true, List.of("Java 17", "PostgreSQL", "Kafka"),
                List.of(new Bullet("E1.H1", E1H1), new Bullet("E1.H2", E1H2), new Bullet("E1.H3", E1H3))));
        assertThat(cv.projects()).hasSize(1);
        assertThat(cv.projects().get(0).name()).isEqualTo("Ledger");
        assertThat(cv.projects().get(0).url()).isEqualTo("https://ledger.example.org");
        assertThat(cv.certifications()).containsExactly(new CertificationItem("C1", "AWS Certified Developer",
                "Amazon", "2022-05", "2025-05"));
        assertThat(cv.education()).containsExactly(new EducationItem("D1", "University of Stellenbosch", "BSc",
                "Computer Science", "2012", "2015"));
        assertThat(cv.languages()).containsExactly(new LanguageItem("English", "Native"),
                new LanguageItem("isiXhosa", "Conversational"));
    }

    @Test
    void aGroundedRephraseIsKept() {
        var a = assembled(good().with(d -> d.experience.set(0, entry("E1",
                h("E1.H3", "Moved billing onto Kafka event streams running on Java 17"),
                h("E1.H1", "Cut API latency 40% for 12,000 users every day")))));
        assertThat(a.findings()).isEmpty();
        assertThat(texts(a.content().experience().get(0).highlights())).containsExactly(
                "Moved billing onto Kafka event streams running on Java 17",
                "Cut API latency 40% for 12,000 users every day");
        assertThat(refs(a.content().experience().get(0).highlights())).as("the model's order")
                .containsExactly("E1.H3", "E1.H1");
    }

    @Test
    void rephrasesAreCleanedAndCut() {
        String longText = "Migrated billing to Kafka event streams " + "and more ".repeat(60);
        var a = assembled(good().with(d -> d.experience.set(0, entry("E1",
                h("E1.H1", "  •  Cut   API latency by 40%\nfor 12,000 daily users "), h("E1.H3", longText)))));
        List<Bullet> bullets = a.content().experience().get(0).highlights();
        assertThat(bullets.get(0).text()).isEqualTo(E1H1);
        assertThat(bullets.get(1).text().length()).isLessThanOrEqualTo(TailoredCvAssembler.MAX_HIGHLIGHT_CHARS);
        assertThat(bullets.get(1).text()).startsWith("Migrated billing to Kafka");
    }

    // ------------------------------------------------------------------ experience (decision f)

    @Test
    void rolesStayInProfileOrderWhateverTheModelsOrder() {
        var a = assembled(good().with(d -> java.util.Collections.reverse(d.experience)));
        assertThat(a.content().experience()).extracting(ExperienceItem::ref).containsExactly("E1", "E2");
        assertThat(a.content().experience()).extracting(ExperienceItem::company).containsExactly("Acme Ltd", "Globex");
        assertThat(a.findings()).isEmpty();
    }

    @Test
    void aMissingRoleGetsItsFirstTwoOriginalHighlights() {
        var a = assembled(good().with(d -> d.experience.remove(0)));
        ExperienceItem e1 = a.content().experience().get(0);
        assertThat(e1.ref()).isEqualTo("E1");
        assertThat(e1.highlights()).containsExactly(new Bullet("E1.H1", E1H1), new Bullet("E1.H2", E1H2));
        assertThat(findings(a, FindingCode.ROLE_DEFAULTED)).singleElement().satisfies(f -> {
            assertThat(f.severity()).isEqualTo(Severity.INFO);
            assertThat(f.path()).isEqualTo("experience[0]");
            assertThat(f.value()).isEqualTo("E1");
            assertThat(f.message()).contains("first 2 points");
        });
    }

    @Test
    void aRoleWhoseHighlightsWereAllDroppedIsDefaulted() {
        var a = assembled(good().with(d -> d.experience.set(1, entry("E2", h("E9.H1", "x"), h("E1.H1", E1H1)))));
        assertThat(a.content().experience().get(1).highlights())
                .containsExactly(new Bullet("E2.H1", E2H1), new Bullet("E2.H2", E2H2));
        assertThat(findings(a, FindingCode.ROLE_DEFAULTED)).extracting(Finding::path).containsExactly("experience[1]");
        assertThat(findings(a, FindingCode.UNKNOWN_REF)).hasSize(2);
    }

    @Test
    void roleDefaultsUseOnlyShownHighlights() {
        ProfileFacts all = facts();
        int budget = all.promptBlock().length() - line("E2.H2", E2H2) - line("P1.H1", P1H1) - line("P2.H1", P2H1);
        ProfileFacts truncated = ProfileFacts.of(profile(), budget, check(), TODAY);
        Draft d = good().with(x -> {
            x.experience.remove(1);
            x.projects.clear();
        });
        var a = assembled(TailoredCvAssembler.assemble(d.build(), truncated, job(), check()));
        assertThat(a.content().experience().get(1).highlights()).as("E2.H2 was not shown to the model")
                .containsExactly(new Bullet("E2.H1", E2H1));
        assertThat(findings(a, FindingCode.ROLE_DEFAULTED)).singleElement()
                .satisfies(f -> assertThat(f.message()).contains("first 1 points"));
        assertThat(findings(a, FindingCode.PROFILE_TRUNCATED)).singleElement().satisfies(f -> {
            assertThat(f.severity()).isEqualTo(Severity.INFO);
            assertThat(f.message()).isEqualTo("Older highlights weren't shown to the model because your profile is "
                    + "long; they're not in this version.");
        });
    }

    @Test
    void aHiddenHighlightRefIsUnknown() {
        ProfileFacts all = facts();
        int budget = all.promptBlock().length() - line("E2.H2", E2H2) - line("P1.H1", P1H1) - line("P2.H1", P2H1);
        ProfileFacts truncated = ProfileFacts.of(profile(), budget, check(), TODAY);
        var a = assembled(TailoredCvAssembler.assemble(good().build(), truncated, job(), check()));
        assertThat(refs(a.content().experience().get(1).highlights())).containsExactly("E2.H1");
        assertThat(findings(a, FindingCode.UNKNOWN_REF)).extracting(Finding::value).contains("E2.H2", "P1.H1");
    }

    private static int line(String ref, String text) {
        return ("  [" + ref + "] <profile_text>" + text + "</profile_text>").length() + 1;
    }

    @Test
    void unknownForeignAndRepeatedHighlightRefsAreDropped() {
        var a = assembled(good().with(d -> d.experience.set(0, entry("E1", h("E1.H1", E1H1), h("E1.H9", "made up"),
                h("E2.H1", E2H1), h("E1.H1", E1H1), h("E1.H2", E1H2)))));
        assertThat(refs(a.content().experience().get(0).highlights())).containsExactly("E1.H1", "E1.H2");
        assertThat(a.findings()).extracting(Finding::code, Finding::path, Finding::value).containsExactly(
                org.assertj.core.groups.Tuple.tuple(FindingCode.UNKNOWN_REF, "experience[0].highlights", "E1.H9"),
                org.assertj.core.groups.Tuple.tuple(FindingCode.UNKNOWN_REF, "experience[0].highlights", "E2.H1"),
                org.assertj.core.groups.Tuple.tuple(FindingCode.DUPLICATE_REF, "experience[0].highlights", "E1.H1"));
        assertThat(a.findings().get(1).message()).contains("under another role");
        assertThat(a.findings()).allMatch(f -> f.severity() == Severity.INFO);
    }

    @Test
    void unknownAndRepeatedRoleRefsAreDropped() {
        var a = assembled(good().with(d -> {
            d.experience.add(entry("E7", h("E7.H1", "x")));
            d.experience.add(entry("E1", h("E1.H1", E1H1)));
            d.experience.add(entry("P1", h("P1.H1", P1H1)));
        }));
        assertThat(a.content().experience()).hasSize(2);
        assertThat(refs(a.content().experience().get(0).highlights())).as("the first E1 entry wins")
                .containsExactly("E1.H1", "E1.H2", "E1.H3");
        assertThat(a.findings()).extracting(Finding::code, Finding::path, Finding::value).containsExactly(
                org.assertj.core.groups.Tuple.tuple(FindingCode.UNKNOWN_REF, "experience", "E7"),
                org.assertj.core.groups.Tuple.tuple(FindingCode.DUPLICATE_REF, "experience", "E1"),
                org.assertj.core.groups.Tuple.tuple(FindingCode.UNKNOWN_REF, "experience", "P1"));
    }

    @Test
    void refsAreNormalised() {
        var a = assembled(good().with(d -> {
            d.experience.set(0, entry(" [e1] ", h("[e1.h2]", E1H2), h(" e1.H3 ", E1H3)));
            d.certifications.set(0, "[c1]");
            d.projects.set(0, entry("p1", h("p1.h1", P1H1)));
        }));
        assertThat(a.findings()).isEmpty();
        assertThat(refs(a.content().experience().get(0).highlights())).containsExactly("E1.H2", "E1.H3");
        assertThat(a.content().certifications()).extracting(CertificationItem::ref).containsExactly("C1");
        assertThat(a.content().projects().get(0).ref()).isEqualTo("P1");
        assertThat(TailoredCvAssembler.ref("[e1.h2]")).isEqualTo("E1.H2");
        assertThat(TailoredCvAssembler.ref("  ")).isNull();
        assertThat(TailoredCvAssembler.ref(null)).isNull();
    }

    @Test
    void highlightRefsKeepTheirPositionsAroundANullHighlight() {
        Experience role = new Experience("Engineer", "Acme", null, "2020", null, true, List.of(),
                Arrays.asList("First point", null, "Third point", "Fourth point"));
        ProfileFacts f = facts(withRoles(role));
        TailoredCvDraft draft = new TailoredCvDraft(null, null, List.of(), List.of(entry("E1",
                h("E1.H3", "Third point"), h("E1.H2", "Second?"))), List.of(), List.of());
        var a = assembled(TailoredCvAssembler.assemble(draft, f, job(), check()));
        assertThat(a.content().experience().get(0).highlights()).containsExactly(new Bullet("E1.H3", "Third point"));
        assertThat(findings(a, FindingCode.UNKNOWN_REF)).extracting(Finding::value).containsExactly("E1.H2");

        TailoredCvDraft none = new TailoredCvDraft(null, null, List.of(), List.of(entry("E1")), List.of(), List.of());
        var defaulted = assembled(TailoredCvAssembler.assemble(none, f, job(), check()));
        assertThat(defaulted.content().experience().get(0).highlights())
                .containsExactly(new Bullet("E1.H1", "First point"), new Bullet("E1.H3", "Third point"));
    }

    @Test
    void atMostSixHighlightsPerRoleAndTheRestAreDroppedSilently() {
        List<String> eight = IntStream.rangeClosed(1, 8).mapToObj(i -> "Point number " + i).toList();
        ProfileFacts f = facts(withRoles(new Experience("Engineer", "Acme", null, "2020", null, true, List.of(),
                eight)));
        List<Highlight> all = IntStream.rangeClosed(1, 8).mapToObj(i -> h("E1.H" + i, "Point number " + i)).toList();
        TailoredCvDraft draft = new TailoredCvDraft(null, null, List.of(), List.of(new Entry("E1", all)), List.of(),
                List.of());
        var a = assembled(TailoredCvAssembler.assemble(draft, f, job(), check()));
        assertThat(refs(a.content().experience().get(0).highlights())).containsExactly("E1.H1", "E1.H2", "E1.H3",
                "E1.H4", "E1.H5", "E1.H6");
        assertThat(a.findings()).isEmpty();
    }

    // ------------------------------------------------------------------ highlight texts (decision g/h)

    @Test
    void aNumberNotInThatHighlightIsReverted() {
        var a = assembled(good().with(d -> d.experience.set(0, entry("E1", h("E1.H1", E1H1),
                h("E1.H2", "Led a team of six engineers"), h("E1.H3", "Migrated billing for 12,000 users to Kafka")))));
        List<Bullet> bullets = a.content().experience().get(0).highlights();
        assertThat(texts(bullets)).containsExactly(E1H1, E1H2, E1H3);
        assertThat(findings(a, FindingCode.REVERTED)).extracting(Finding::path, Finding::value).containsExactly(
                org.assertj.core.groups.Tuple.tuple("experience[0].highlights[1]", "six"),
                org.assertj.core.groups.Tuple.tuple("experience[0].highlights[2]", "12,000"));
        assertThat(findings(a, FindingCode.REVERTED).get(0).message())
                .isEqualTo("The reworded point had a number not in this point of your profile, so your profile's "
                        + "wording is used.");
        assertThat(findings(a, FindingCode.REVERTED)).allMatch(f -> f.severity() == Severity.INFO);
    }

    @Test
    void aSkillFromAnotherRoleIsReverted() {
        var a = assembled(good().with(d -> d.experience.set(1, entry("E2", h("E2.H1", E2H1),
                h("E2.H2", "Containerised Kafka services with Docker in 2018")))));
        assertThat(a.content().experience().get(1).highlights().get(1).text()).isEqualTo(E2H2);
        assertThat(findings(a, FindingCode.REVERTED)).singleElement().satisfies(f -> {
            assertThat(f.value()).isEqualTo("Kafka");
            assertThat(f.message()).contains("a skill not in this role");
        });
    }

    @Test
    void aProjectHighlightWithAForeignSkillNamesTheProject() {
        var a = assembled(good().with(d -> d.projects.set(0, entry("P1",
                h("P1.H1", "Wrote the core ledger engine in React with 95% test coverage")))));
        assertThat(a.content().projects().get(0).highlights().get(0).text()).isEqualTo(P1H1);
        assertThat(findings(a, FindingCode.REVERTED)).singleElement()
                .satisfies(f -> assertThat(f.message()).contains("a skill not in this project"));
    }

    @Test
    void contactDetailsAreReverted() {
        var a = assembled(good().with(d -> d.experience.set(0, entry("E1",
                h("E1.H1", "Cut API latency by 40% for 12,000 daily users, see zoe@example.com")))));
        assertThat(a.content().experience().get(0).highlights().get(0).text()).isEqualTo(E1H1);
        assertThat(findings(a, FindingCode.REVERTED)).singleElement().satisfies(f -> {
            assertThat(f.value()).isEqualTo("zoe@example.com");
            assertThat(f.message()).contains("contact details");
        });
    }

    @Test
    void anEmptyRephraseIsReverted() {
        var a = assembled(good().with(d -> d.experience.set(0, entry("E1", h("E1.H1", "   "), h("E1.H2", null)))));
        assertThat(texts(a.content().experience().get(0).highlights())).containsExactly(E1H1, E1H2);
        assertThat(findings(a, FindingCode.REVERTED)).hasSize(2)
                .allSatisfy(f -> assertThat(f.message()).contains("an empty text"));
    }

    @Test
    void aHeavilyRewordedRephraseIsKeptWithAWarning() {
        var a = assembled(good().with(d -> d.experience.set(0, entry("E1",
                h("E1.H1", "Made everything quicker for everybody around"), h("E1.H2", E1H2)))));
        assertThat(a.content().experience().get(0).highlights().get(0).text())
                .isEqualTo("Made everything quicker for everybody around");
        assertThat(findings(a, FindingCode.HEAVILY_REWORDED)).singleElement().satisfies(f -> {
            assertThat(f.severity()).isEqualTo(Severity.WARNING);
            assertThat(f.path()).isEqualTo("experience[0].highlights[0]");
        });
    }

    @Test
    void jobVocabularyCountsAsSourceWords() {
        // "senior", "developer" come from the job title; "kubernetes" is not allowed (a skill), so use title words
        var a = assembled(good().with(d -> d.experience.set(0, entry("E1",
                h("E1.H1", "Senior developer work: latency down 40% for 12,000 users")))));
        assertThat(findings(a, FindingCode.HEAVILY_REWORDED)).isEmpty();
        assertThat(findings(a, FindingCode.REVERTED)).isEmpty();
    }

    // ------------------------------------------------------------------ projects and certifications

    @Test
    void projectsFollowTheModelsOrderAndBadRefsAreDropped() {
        var a = assembled(good().with(d -> {
            d.projects.clear();
            d.projects.add(entry("P2", h("P2.H1", P2H1)));
            d.projects.add(entry("P9", h("P9.H1", "x")));
            d.projects.add(entry("P1", h("P1.H1", P1H1)));
            d.projects.add(entry("P2"));
        }));
        assertThat(a.content().projects()).extracting(p -> p.ref()).containsExactly("P2", "P1");
        assertThat(a.findings()).extracting(Finding::code, Finding::path, Finding::value).containsExactly(
                org.assertj.core.groups.Tuple.tuple(FindingCode.UNKNOWN_REF, "projects", "P9"),
                org.assertj.core.groups.Tuple.tuple(FindingCode.DUPLICATE_REF, "projects", "P2"));
    }

    @Test
    void atMostFourProjectsAndFourHighlightsPerProject() {
        List<Project> projects = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            projects.add(new Project("Project " + i, null, List.of(), null,
                    IntStream.rangeClosed(1, 5).mapToObj(j -> "Detail point " + j).toList()));
        }
        ProfileDocument p = new ProfileDocument(NAME, null, null, null, null, null, List.of(), List.of(e1()),
                projects, List.of(), List.of(), List.of(), List.of());
        List<Entry> chosen = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            int n = i;
            chosen.add(new Entry("P" + i, IntStream.rangeClosed(1, 5).mapToObj(j -> h("P" + n + ".H" + j,
                    "Detail point " + j)).toList()));
        }
        TailoredCvDraft draft = new TailoredCvDraft(null, null, List.of(), List.of(entry("E1", h("E1.H1", E1H1))),
                chosen, List.of());
        var a = assembled(TailoredCvAssembler.assemble(draft, facts(p), job(), check()));
        assertThat(a.content().projects()).extracting(x -> x.ref()).containsExactly("P1", "P2", "P3", "P4");
        assertThat(a.content().projects()).allSatisfy(x -> assertThat(refs(x.highlights())).hasSize(4));
        assertThat(refs(a.content().projects().get(0).highlights())).containsExactly("P1.H1", "P1.H2", "P1.H3",
                "P1.H4");
        assertThat(a.findings()).as("over-cap items are dropped silently").isEmpty();
    }

    @Test
    void certificationsFollowTheModelsOrderAndBadRefsAreDropped() {
        var a = assembled(good().with(d -> {
            d.certifications.add("C9");
            d.certifications.add("C1");
            d.certifications.add(null);
        }));
        assertThat(a.content().certifications()).extracting(CertificationItem::ref).containsExactly("C1");
        assertThat(a.findings()).extracting(Finding::code, Finding::value).containsExactly(
                org.assertj.core.groups.Tuple.tuple(FindingCode.UNKNOWN_REF, "C9"),
                org.assertj.core.groups.Tuple.tuple(FindingCode.DUPLICATE_REF, "C1"),
                org.assertj.core.groups.Tuple.tuple(FindingCode.UNKNOWN_REF, null));
        assertThat(a.findings()).allMatch(f -> "certifications".equals(f.path()));
    }

    // ------------------------------------------------------------------ skills

    @Test
    void skillsAreResolvedToTheProfilesSpelling() {
        var a = assembled(good().with(d -> {
            d.skills.clear();
            d.skills.addAll(List.of("java", "postgres", "JAVA", "golang", "Kubernetes"));
        }));
        assertThat(a.content().skills()).containsExactly("Java", "PostgreSQL", "Go");
        assertThat(findings(a, FindingCode.SKILL_NOT_IN_PROFILE)).singleElement().satisfies(f -> {
            assertThat(f.severity()).isEqualTo(Severity.INFO);
            assertThat(f.path()).isEqualTo("skills");
            assertThat(f.value()).isEqualTo("Kubernetes");
        });
    }

    @Test
    void noUsableSkillsGivesTheProfileSkillsWithTheJobsFirst() {
        var a = assembled(good().with(d -> {
            d.skills.clear();
            d.skills.addAll(List.of("Kubernetes", "React"));
        }));
        // job: Java and Kubernetes required, Docker nice
        assertThat(a.content().skills()).containsExactly("Java", "Docker", "PostgreSQL", "Kafka", "Python", "Go",
                "HTML5");
        assertThat(findings(a, FindingCode.SKILLS_DEFAULTED)).singleElement()
                .satisfies(f -> assertThat(f.severity()).isEqualTo(Severity.INFO));
        assertThat(findings(a, FindingCode.SKILL_NOT_IN_PROFILE)).hasSize(2);

        var empty = assembled(good().with(d -> d.skills.clear()));
        assertThat(empty.content().skills()).startsWith("Java", "Docker");
        assertThat(findings(empty, FindingCode.SKILLS_DEFAULTED)).hasSize(1);
    }

    @Test
    void atMostTwentySkills() {
        List<SkillEntry> skills = IntStream.rangeClosed(1, 25).mapToObj(i -> new SkillEntry("Skill" + i, null))
                .toList();
        ProfileDocument p = new ProfileDocument(NAME, null, null, null, null, null, List.of(), List.of(e1()),
                List.of(), skills, List.of(), List.of(), List.of());
        List<String> chosen = IntStream.rangeClosed(1, 25).mapToObj(i -> "skill" + i).toList();
        TailoredCvDraft draft = new TailoredCvDraft(null, null, chosen, List.of(entry("E1", h("E1.H1", E1H1))),
                List.of(), List.of());
        var a = assembled(TailoredCvAssembler.assemble(draft, facts(p), job(), check()));
        assertThat(a.content().skills()).hasSize(20).startsWith("Skill1", "Skill2").endsWith("Skill20");
        assertThat(a.findings()).isEmpty();

        TailoredCvDraft none = new TailoredCvDraft(null, null, List.of(), List.of(entry("E1", h("E1.H1", E1H1))),
                List.of(), List.of());
        assertThat(assembled(TailoredCvAssembler.assemble(none, facts(p), job(), check())).content().skills())
                .as("the default is capped too").hasSize(20);
    }

    // ------------------------------------------------------------------ headline and summary

    @Test
    void nullHeadlineAndSummaryKeepTheProfiles() {
        var a = assembled(good());
        assertThat(a.content().headline()).isEqualTo("Backend engineer");
        assertThat(a.content().summary()).isEqualTo("Backend engineer with 7 years of Java and PostgreSQL.");
    }

    @Test
    void aGroundedHeadlineAndSummaryAreUsed() {
        var a = assembled(good().with(d -> {
            d.headline = "Java and Kafka backend engineer";
            d.summary = "Backend engineer with 10 years of experience, 7 of them in Java.";
        }));
        assertThat(a.findings()).isEmpty();
        assertThat(a.content().headline()).isEqualTo("Java and Kafka backend engineer");
        assertThat(a.content().summary()).as("the career span is allowed in the summary")
                .isEqualTo("Backend engineer with 10 years of experience, 7 of them in Java.");
    }

    @Test
    void anInventedNumberInTheHeadlineOrSummaryIsReverted() {
        var a = assembled(good().with(d -> {
            d.headline = "Backend engineer, 10 years";
            d.summary = "Backend engineer who spent 4 years at Globex.";
        }));
        assertThat(a.content().headline()).as("the career span is summary-only").isEqualTo("Backend engineer");
        assertThat(a.content().summary()).as("role durations are letter-only")
                .isEqualTo("Backend engineer with 7 years of Java and PostgreSQL.");
        assertThat(findings(a, FindingCode.REVERTED)).extracting(Finding::path, Finding::value).containsExactly(
                org.assertj.core.groups.Tuple.tuple("headline", "10"),
                org.assertj.core.groups.Tuple.tuple("summary", "4"));
        assertThat(findings(a, FindingCode.REVERTED).get(0).message())
                .isEqualTo("The reworded headline had a number not in your profile, so your profile's headline is used.");
    }

    @Test
    void aForeignSkillOrContactDetailsInTheSummaryAreReverted() {
        var skill = assembled(good().with(d -> d.summary = "Backend engineer who runs Kubernetes clusters."));
        assertThat(findings(skill, FindingCode.REVERTED)).singleElement().satisfies(f -> {
            assertThat(f.value()).isEqualTo("Kubernetes");
            assertThat(f.message()).contains("a skill not in your profile");
        });
        var contact = assembled(good().with(d -> d.headline = "Backend engineer, call 082 555 0199"));
        assertThat(contact.content().headline()).isEqualTo("Backend engineer");
        assertThat(findings(contact, FindingCode.REVERTED)).singleElement()
                .satisfies(f -> assertThat(f.message()).contains("contact details"));
    }

    @Test
    void anOverlongHeadlineOrSummaryIsCutAtAWord() {
        String longSummary = "Backend engineer building reliable services. ".repeat(25);
        String longHeadline = "Backend engineer ".repeat(10);
        var a = assembled(good().with(d -> {
            d.summary = longSummary;
            d.headline = longHeadline;
        }));
        assertThat(a.content().summary().length()).isLessThanOrEqualTo(900);
        assertThat(a.content().headline().length()).isLessThanOrEqualTo(120);
        assertThat(longSummary).startsWith(a.content().summary());
        assertThat(a.content().summary()).doesNotEndWith(" ").endsWith("services.");
        assertThat(findings(a, FindingCode.LENGTH)).extracting(Finding::path, Finding::severity).containsExactly(
                org.assertj.core.groups.Tuple.tuple("headline", Severity.WARNING),
                org.assertj.core.groups.Tuple.tuple("summary", Severity.WARNING));
        assertThat(TailoredCvAssembler.cutAtWord("aaaa bbbb cccc", 9)).isEqualTo("aaaa bbbb");
        assertThat(TailoredCvAssembler.cutAtWord("aaaaaaaaaaaa", 5)).isEqualTo("aaaaa");
    }

    // ------------------------------------------------------------------ rejection (decision h)

    /** E1 with E1.H1 (2 good items), an unknown role ref and an unknown skill: 4 items, 2 dropped. */
    private static Draft halfBad() {
        Draft d = new Draft();
        d.experience.add(entry("E1", h("E1.H1", E1H1)));
        d.experience.add(entry("E9"));
        d.skills.add("Kubernetes");
        return d;
    }

    @Test
    void exactlyHalfTheItemsRepairedIsNotRejected() {
        var a = assembled(halfBad());
        assertThat(findings(a, FindingCode.UNKNOWN_REF)).hasSize(1);
        assertThat(findings(a, FindingCode.SKILL_NOT_IN_PROFILE)).hasSize(1);
    }

    @Test
    void moreThanHalfTheItemsRepairedIsRejected() {
        AssemblyResult<TailoredCvContent> r = run(halfBad().with(d -> d.skills.add("React")));
        assertThat(r).isInstanceOf(AssemblyResult.Rejected.class);
        assertThat(((AssemblyResult.Rejected<TailoredCvContent>) r).reason()).isEqualTo("3 of 5 items dropped or reverted");
    }

    @Test
    void aDroppedEntryCountsItsHighlightsToo() {
        // E1 + H1 good (2); E9 with 2 highlights = 3 items, all dropped: 3 of 5
        AssemblyResult<TailoredCvContent> r = run(new Draft().with(d -> {
            d.experience.add(entry("E1", h("E1.H1", E1H1)));
            d.experience.add(entry("E9", h("E9.H1", "x"), h("E9.H2", "y")));
        }));
        assertThat(r).isInstanceOf(AssemblyResult.Rejected.class);
    }

    @Test
    void revertedHighlightsCountTowardsRejection() {
        AssemblyResult<TailoredCvContent> r = run(new Draft().with(d -> d.experience.add(entry("E1",
                h("E1.H1", "Cut latency by 41%"), h("E1.H2", "Led a team of nine engineers"), h("E1.H3", E1H3)))));
        assertThat(r).as("2 of 4 reverted").isInstanceOf(AssemblyResult.Assembled.class);
        AssemblyResult<TailoredCvContent> over = run(new Draft().with(d -> d.experience.add(entry("E1",
                h("E1.H1", "Cut latency by 41%"), h("E1.H2", "Led a team of nine engineers"),
                h("E1.H3", "Migrated 3 billing systems to Kafka")))));
        assertThat(over).as("3 of 4 reverted").isInstanceOf(AssemblyResult.Rejected.class);
    }

    @Test
    void fewerThanFourItemsAreNeverRejected() {
        var a = assembled(new Draft().with(d -> {
            d.experience.add(entry("E1"));
            d.experience.add(entry("E9"));
            d.skills.add("Kubernetes");
        }));
        assertThat(findings(a, FindingCode.ROLE_DEFAULTED)).hasSize(2);
    }

    @Test
    void headlineAndSummaryRevertsDoNotCountTowardsRejection() {
        var a = assembled(halfBad().with(d -> {
            d.headline = "Engineer with 99 years";
            d.summary = "Engineer who knows Terraform.";
        }));
        assertThat(findings(a, FindingCode.REVERTED)).extracting(Finding::path).containsExactly("headline", "summary");
    }

    @Test
    void vocabularyUnavailableRevertsDoNotCountTowardsRejection() {
        ProfileFacts f = ProfileFacts.of(profile(), 12000, FactCheck.unavailable(), TODAY);
        TailoredCvDraft draft = new TailoredCvDraft(null, null, List.of("Java"), List.of(entry("E1",
                h("E1.H1", "Cut API latency 40% for 12,000 users"), h("E1.H2", "Led five engineers as a team"),
                h("E1.H3", "Moved billing onto Kafka event streams"))), List.of(), List.of());
        var a = assembled(TailoredCvAssembler.assemble(draft, f, job(), FactCheck.unavailable()));
        assertThat(texts(a.content().experience().get(0).highlights())).containsExactly(E1H1, E1H2, E1H3);
        assertThat(findings(a, FindingCode.REVERTED)).hasSize(3).allSatisfy(x -> {
            assertThat(x.value()).isNull();
            assertThat(x.message()).contains("Skills couldn't be checked");
        });
        assertThat(a.content().skills()).as("direct profile names still resolve").containsExactly("Java");
    }

    @Test
    void noExperienceEntriesIsRejectedWhenTheProfileHasRoles() {
        AssemblyResult<TailoredCvContent> r = run(good().with(d -> d.experience.clear()));
        assertThat(r).isEqualTo(new AssemblyResult.Rejected<TailoredCvContent>("no experience entries"));
        assertThat(TailoredCvAssembler.assemble(new TailoredCvDraft(null, null, null, null, null, null), facts(),
                job(), check())).isInstanceOf(AssemblyResult.Rejected.class);
        assertThat(TailoredCvAssembler.assemble(null, facts(), job(), check()))
                .isEqualTo(new AssemblyResult.Rejected<TailoredCvContent>("no answer"));
    }

    @Test
    void aProfileWithoutRolesNeedsNoExperienceEntries() {
        ProfileDocument p = new ProfileDocument(NAME, null, null, null, "Graduate", null, List.of(), List.of(),
                List.of(), List.of(new SkillEntry("Java", null)), List.of(), List.of(), List.of());
        TailoredCvDraft draft = new TailoredCvDraft(null, null, List.of("Java"), List.of(), List.of(), List.of());
        var a = assembled(TailoredCvAssembler.assemble(draft, facts(p), null, null));
        assertThat(a.content().experience()).isEmpty();
        assertThat(a.content().skills()).containsExactly("Java");
    }

    @Test
    void nullsInTheAnswerAreTolerated() {
        TailoredCvDraft draft = new TailoredCvDraft(null, null, Arrays.asList("Java", null),
                Arrays.asList(new Entry("E1", Arrays.asList(h("E1.H1", E1H1), null)), null,
                        new Entry("E2", null)), null, Arrays.asList((String) null));
        var a = assembled(TailoredCvAssembler.assemble(draft, facts(), job(), check()));
        assertThat(a.content().experience()).hasSize(2);
        assertThat(refs(a.content().experience().get(0).highlights())).containsExactly("E1.H1");
        assertThat(findings(a, FindingCode.ROLE_DEFAULTED)).extracting(Finding::value).containsExactly("E2");
    }
}
