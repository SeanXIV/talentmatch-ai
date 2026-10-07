package com.talentmatch.profile;

import static com.talentmatch.profile.ProfileFixtures.exp;
import static com.talentmatch.profile.ProfileFixtures.skill;
import static com.talentmatch.profile.ProfileFixtures.withExperience;
import static com.talentmatch.profile.ProfileFixtures.withSkills;
import static org.assertj.core.api.Assertions.assertThat;

import com.talentmatch.profile.ProfileDocument.Certification;
import com.talentmatch.profile.ProfileDocument.Education;
import com.talentmatch.profile.ProfileDocument.Project;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Phase 4: never-invent grounding of AI drafts against the CV text (test plan; B3 years, S13). */
class ResumeGroundingTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-05T12:00:00Z"), ZoneOffset.UTC);

    private static ResumeGrounding.Result ground(ProfileDocument d, String cv) {
        return ResumeGrounding.apply(d, cv, CLOCK);
    }

    private static List<String> paths(ResumeGrounding.Result r) {
        return r.warnings().stream().map(ProfileWarning::path).toList();
    }

    @Test
    void javaIsNotFoundInAJavaScriptOnlyCv() {
        assertThat(paths(ground(withSkills(skill("Java", null), skill("JavaScript", null)),
                "Frontend developer: JavaScript, TypeScript, React.")))
                .containsExactly("skills[0].name");
    }

    @Test
    void spellingVariantsMatch() {
        String cv = "Built services in NodeJS and node js; also C++ and C# on .NET; Postgre SQL";
        assertThat(paths(ground(withSkills(skill("Node.js", null), skill("C++", null), skill("C#", null),
                skill(".NET", null), skill("PostgreSQL", null)), cv))).isEmpty();
    }

    @Test
    void cPlusPlusIsNotCAndCSharpIsNotC() {
        assertThat(paths(ground(withSkills(skill("C", null), skill("C#", null)), "Languages: C++ only, 2010-2020")))
                .containsExactly("skills[0].name", "skills[1].name");
    }

    @Test
    void caseAndPunctuationIgnored() {
        assertThat(paths(ground(withSkills(skill("kubernetes", null), skill("CI/CD", null)),
                "KUBERNETES clusters, CI-CD pipelines"))).isEmpty();
    }

    @Test
    void ligaturesAndSoftHyphensInEitherSideMatch() {
        ProfileDocument d = ProfileFixtures.withCertifications(
                new Certification("Certified Kubernetes Administrator", null, null, null, null, null));
        assertThat(paths(ground(d, "Certiﬁed Kuber­netes Administrator"))).isEmpty();
        assertThat(ResumeGrounding.key("Certiﬁed​ Admin")).isEqualTo("certified admin");
    }

    @Test
    void companyOrTitleNotInCvIsWarned() {
        ResumeGrounding.Result r = ground(withExperience(exp("Staff Engineer", "Globex", "2020", null, true)),
                "Senior Engineer at Acme Ltd since 2020");
        assertThat(paths(r)).containsExactlyInAnyOrder("experience[0].company", "experience[0].title");
        assertThat(r.warnings().get(0).message()).contains("not found in your CV");
        assertThat(r.document()).as("names are never deleted, only warned").isEqualTo(
                withExperience(exp("Staff Engineer", "Globex", "2020", null, true)));
    }

    @Test
    void technologiesProjectsCertificationsAndEducationAreChecked() {
        String cv = "Acme Ltd Engineer 2020. Project Atlas with Java. AWS Certified by Amazon. MIT BSc.";
        ProfileDocument d = new ProfileDocument("Ada", null, null, null, null, null, List.of(),
                List.of(exp("Engineer", "Acme Ltd", "2020", null, true, List.of("Java", "Rust"), List.of())),
                List.of(new Project("Atlas", null, List.of("Java", "Go"), null, List.of()),
                        new Project("Zeus", null, List.of(), null, List.of())),
                List.of(), List.of(new Certification("AWS Certified", "Microsoft", null, null, null, null)),
                List.of(new Education("MIT", "PhD", null, null, null)), List.of());
        assertThat(paths(ground(d, cv))).containsExactlyInAnyOrder("experience[0].technologies[1]",
                "projects[0].technologies[1]", "projects[1].name", "certifications[0].issuer",
                "education[0].qualification");
    }

    @Test
    void nullValuesAreNotWarned() {
        ProfileDocument d = ProfileFixtures.withEducation(new Education("MIT", null, null, null, null));
        assertThat(paths(ground(d, "MIT"))).isEmpty();
    }

    // ------------------------------------------------------------------ B3 years

    @Test
    void statedYearsNearTheSkillAreKept() {
        for (String cv : List.of("Java (5 years)", "5 years of Java", "5+ years Java", "Java: five years",
                "Java developer with 5 years experience")) {
            ResumeGrounding.Result r = ground(withSkills(skill("Java", 5)), cv);
            assertThat(r.document().skills().get(0).years()).as(cv).isEqualTo(5);
            assertThat(r.warnings()).as(cv).isEmpty();
        }
    }

    @Test
    void ungroundedYearsBecomeNullWithAWarning() {
        ResumeGrounding.Result r = ground(withSkills(skill("Java", 5), skill("SQL", null)), "Java and SQL developer");
        assertThat(r.document().skills().get(0).years()).isNull();
        assertThat(r.document().skills().get(1).years()).isNull();
        assertThat(paths(r)).containsExactly("skills[0].years");
        assertThat(r.warnings().get(0).value()).isEqualTo("5");
    }

    @Test
    void yearsFarFromTheSkillOrBelongingToAnotherNumberAreDropped() {
        String far = "Java" + " filler".repeat(20) + " 5 years of management";
        assertThat(ground(withSkills(skill("Java", 5)), far).document().skills().get(0).years()).isNull();
        assertThat(ground(withSkills(skill("Java", 5)), "Java 15 years").document().skills().get(0).years())
                .as("5 inside 15 is not 5").isNull();
        assertThat(ground(withSkills(skill("Java", 5)), "JavaScript 5 years").document().skills().get(0).years())
                .as("number near JavaScript, not Java").isNull();
    }

    @Test
    void yearsLongerThanTheCareerAreWarned() {
        String cv = "Engineer at Acme 2022 - Present. Java (10 years)";
        ProfileDocument d = ProfileFixtures.doc("Ada", null,
                List.of(exp("Engineer", "Acme", "2022", null, true)), List.of(skill("Java", 10)));
        ResumeGrounding.Result r = ground(d, cv);
        assertThat(r.document().skills().get(0).years()).as("grounded, so kept").isEqualTo(10);
        assertThat(paths(r)).containsExactly("skills[0].years");
        assertThat(r.warnings().get(0).message()).contains("5 years"); // 2022..2026
    }

    @Test
    void checkReturnsWarningsOnly() {
        assertThat(ResumeGrounding.check(withSkills(skill("Rust", null)), "Java")).hasSize(1);
    }
}
