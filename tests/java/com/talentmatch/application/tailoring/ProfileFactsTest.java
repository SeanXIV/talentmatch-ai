package com.talentmatch.application.tailoring;

import static com.talentmatch.application.tailoring.TailoringFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.talentmatch.application.content.Header;
import com.talentmatch.application.content.LinkItem;
import com.talentmatch.profile.ProfileDocument;
import com.talentmatch.profile.ProfileDocument.Experience;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/** §9.1.2: refs, the prompt block (no contact data), allowed sets, the budget and sanitisation. */
class ProfileFactsTest {

    @Test
    void refsFollowProfileListOrder() {
        ProfileFacts f = facts();
        assertThat(f.experienceRefs()).containsExactly("E1", "E2");
        assertThat(f.projectRefs()).containsExactly("P1", "P2");
        assertThat(f.certificationRefs()).containsExactly("C1");
        assertThat(f.educationRefs()).containsExactly("D1");
        assertThat(f.highlight("E1.H2").text()).isEqualTo(E1H2);
        assertThat(f.highlight("E1.H2").parentRef()).isEqualTo("E1");
        assertThat(f.highlight("E2.H1").text()).isEqualTo(E2H1);
        assertThat(f.highlight("P1.H1").parentRef()).isEqualTo("P1");
        assertThat(f.highlight("P2.H1").text()).isEqualTo(P2H1);
        assertThat(f.highlight("E3.H1")).isNull();
        assertThat(f.highlight(null)).isNull();
        assertThat(f.highlightsOf("E1")).extracting(ProfileFacts.Highlight::ref)
                .containsExactly("E1.H1", "E1.H2", "E1.H3");
        assertThat(f.highlightsOf("E1")).allMatch(ProfileFacts.Highlight::shown);
        assertThat(f.indexOf("E2")).isEqualTo(1);
        assertThat(f.indexOf("P2")).isEqualTo(1);
        assertThat(f.indexOf("C1")).isZero();
        assertThat(f.indexOf("D1")).isZero();
        assertThat(f.indexOf("E3")).isEqualTo(-1);
        assertThat(f.indexOf("X1")).isEqualTo(-1);
        assertThat(f.indexOf("")).isEqualTo(-1);
        assertThat(f.truncated()).isFalse();
    }

    @Test
    void aNullHighlightKeepsTheNumberingOfTheOthers() {
        Experience role = new Experience("Engineer", "Acme", null, "2020", null, true, List.of(),
                Arrays.asList("First point", null, "Third point"));
        ProfileFacts f = facts(withRoles(role));
        assertThat(f.highlight("E1.H1").text()).isEqualTo("First point");
        assertThat(f.highlight("E1.H2")).isNull();
        assertThat(f.highlight("E1.H3").text()).as("E1.H3 is the third highlight").isEqualTo("Third point");
        assertThat(f.promptBlock()).contains("  [E1.H3] <profile_text>Third point</profile_text>")
                .doesNotContain("[E1.H2]");
    }

    @Test
    void thePromptBlockListsTheFactsWithRefs() {
        String block = facts().promptBlock();
        assertThat(block).startsWith(ProfileFacts.BLOCK_HEADER + "\n").doesNotEndWith("\n");
        assertThat(block).contains(
                "HEADLINE: <profile_text>Backend engineer</profile_text>",
                "SUMMARY: <profile_text>Backend engineer with 7 years of Java and PostgreSQL.</profile_text>",
                "SKILLS: Java (7 years); PostgreSQL (5 years); Kafka; Python (3 years); Docker; Go; HTML5",
                "[E1] Senior Backend Engineer | Acme Ltd | 2021-03 to present | technologies: Java 17, PostgreSQL, Kafka",
                "  [E1.H1] <profile_text>" + E1H1 + "</profile_text>",
                "[E2] Backend Engineer | Globex | 2016-01 to 2020-12 | technologies: Python, Docker",
                "[P1] Ledger | technologies: Go | <profile_text>Open-source double-entry ledger</profile_text>",
                "[C1] AWS Certified Developer | Amazon | 2022-05",
                "[D1] BSc, Computer Science | University of Stellenbosch | 2012 to 2015",
                "LANGUAGES: English (Native); isiXhosa (Conversational)");
        assertThat(block.indexOf("[E1]")).isLessThan(block.indexOf("[E1.H1]"));
        assertThat(block.indexOf("[E1.H3]")).isLessThan(block.indexOf("[E2]"));
    }

    @Test
    void thePromptBlockHasNoContactDataCredentialIdsOrCertificateUrls() {
        String block = facts().promptBlock();
        assertThat(block).doesNotContain(NAME, "Zoë", "Ndlovu", EMAIL, "example.com", PHONE, "555", LOCATION,
                "Cape Town", "Johannesburg", GITHUB, "github", CREDENTIAL_ID, CERT_URL, "verify.example",
                "ledger.example.org");
    }

    @Test
    void anEmptyProfileGetsNoneMarkers() {
        ProfileDocument empty = new ProfileDocument(NAME, null, null, null, null, null, null, null, null, null,
                null, null, null);
        ProfileFacts f = facts(empty);
        assertThat(f.promptBlock()).contains("HEADLINE: <profile_text>(none)</profile_text>", "SKILLS: (none)",
                "EXPERIENCE\n(none)", "PROJECTS\n(none)", "LANGUAGES: (none)");
        assertThat(f.experienceRefs()).isEmpty();
        assertThat(f.careerSpanYears()).isNull();
    }

    @Test
    void untrustedTextIsSanitised() {
        Experience role = new Experience("Dev <script> & {{name}}", "Acme", null, "2020", null, true, List.of(),
                List.of("Used <b>bold</b> & {{template}} tricks\nover\ttwo lines\u0007"));
        String block = facts(withRoles(role)).promptBlock();
        assertThat(block).contains("[E1] Dev &lt;script&gt; &amp; { {name} } | Acme")
                .contains("<profile_text>Used &lt;b&gt;bold&lt;/b&gt; &amp; { {template} } tricks over two lines"
                        + "</profile_text>")
                .doesNotContain("{{", "}}", "\u0007", "<b>", "<script>");
    }

    @Test
    void allowedNumbersPerHighlightAreItsOwnPlusItsRolesTechnologies() {
        ProfileFacts f = facts();
        assertThat(f.numbers("E1.H1")).contains("40", "12000", "17").doesNotContain("5", "2", "95");
        assertThat(f.numbers("E1.H2")).as("'five' counts as 5").contains("5", "17").doesNotContain("40", "12000");
        assertThat(f.numbers("E2.H1")).contains("2").doesNotContain("17", "40");
        assertThat(f.numbers("P1.H1")).contains("95");
        assertThat(f.numbers("E9.H9")).isEmpty();
    }

    @Test
    void globalNumbersAddDateYearsSkillYearsTheCareerSpanAndRoleDurations() {
        ProfileFacts f = facts();
        assertThat(f.careerSpanYears()).as("2016-01 to 2026-10").isEqualTo(10);
        assertThat(f.roleDurations()).as("E1 2021-03..2026-10 = 5; E2 2016-01..2020-12 = 4")
                .containsExactlyInAnyOrder("5", "4");
        assertThat(f.statedNumbers()).contains("7", "5", "3", "40", "12000", "2", "95", "17", "2021", "2016", "2020",
                "2022", "2025", "2012", "2015", "2018").doesNotContain("10", "4");
        assertThat(f.summaryNumbers()).contains("10").doesNotContain("4");
        assertThat(f.numbersGlobal()).contains("10", "4", "5", "40", "2021");
        assertThat(f.statedNumbers()).as("the phone number is never an allowed number")
                .doesNotContain("27", "82", "555", "0199");
    }

    @Test
    void allowedYearsAreDateYearsPlusYearsWrittenInTheProfile() {
        assertThat(facts().allowedYears()).containsExactlyInAnyOrder("2021", "2016", "2020", "2022", "2025",
                "2012", "2015", "2018");
    }

    @Test
    void theCareerSpanCountsMonthsAndAYearOnlyDateIsJanuary() {
        assertThat(span("2019", "2021-12")).as("Jan 2019..Dec 2021 = 35 months").isEqualTo(2);
        assertThat(span("2019-12", "2021-11")).as("23 months").isEqualTo(1);
        assertThat(span("2019-02", "2021")).as("Feb 2019..Jan 2021 = 23 months").isEqualTo(1);
        assertThat(span("2019-01", "2021-01")).isEqualTo(2);
        ProfileFacts f = facts(withRoles(new Experience("Dev", "Acme", null, "2019-12", "2021-11", false, List.of(),
                List.of())));
        assertThat(f.roleDurations()).containsExactly("1");
        ProfileFacts shortRole = facts(withRoles(new Experience("Dev", "Acme", null, "2021-01", "2021-06", false,
                List.of(), List.of())));
        assertThat(shortRole.roleDurations()).as("only positive durations").isEmpty();
        assertThat(shortRole.careerSpanYears()).isZero();
    }

    @Test
    void aCurrentRoleRunsToToday() {
        ProfileFacts f = facts(withRoles(new Experience("Dev", "Acme", null, "2023-11", null, true, List.of(),
                List.of())));
        assertThat(f.careerSpanYears()).as("2023-11..2026-10 = 35 months").isEqualTo(2);
    }

    private static Integer span(String start, String end) {
        return facts(withRoles(new Experience("Dev", "Acme", null, start, end, false, List.of(), List.of())))
                .careerSpanYears();
    }

    @Test
    void skillSetsPerHighlightAndGlobal() {
        ProfileFacts f = facts();
        assertThat(f.skills("E1.H1")).contains(id("Java"), id("PostgreSQL"), id("Kafka"))
                .doesNotContain(id("Python"), id("Docker"));
        assertThat(f.skills("E2.H1")).contains(id("Python"), id("Docker")).doesNotContain(id("Kafka"), id("Java"));
        assertThat(f.skills("P2.H1")).contains(id("HTML5"));
        assertThat(f.skillsGlobal()).contains(id("Java"), id("PostgreSQL"), id("Kafka"), id("Python"), id("Docker"),
                id("Go"), id("HTML5")).doesNotContain(id("Kubernetes"), id("React"), id("Terraform"));
    }

    @Test
    void withoutAVocabularyTheSkillSetsAreEmpty() {
        ProfileFacts f = ProfileFacts.of(profile(), 12000, FactCheck.unavailable(), TODAY);
        assertThat(f.skillsGlobal()).isEmpty();
        assertThat(f.skills("E1.H1")).isEmpty();
        assertThat(f.numbers("E1.H1")).contains("40");
        assertThat(f.profileSkill("java", FactCheck.unavailable())).contains("Java");
        assertThat(f.profileSkill("postgres", FactCheck.unavailable())).isEmpty();
    }

    @Test
    void profileSkillUsesTheProfilesSpellingDirectlyOrByAlias() {
        ProfileFacts f = facts();
        assertThat(f.profileSkill(" JAVA ", check())).contains("Java");
        assertThat(f.profileSkill("postgres", check())).contains("PostgreSQL");
        assertThat(f.profileSkill("golang", check())).contains("Go");
        assertThat(f.profileSkill("Kubernetes", check())).isEmpty();
        assertThat(f.profileSkill("Cobol", check())).isEmpty();
        assertThat(f.profileSkill(" ", check())).isEmpty();
        assertThat(f.skillNames()).containsExactly("Java", "PostgreSQL", "Kafka", "Python", "Docker", "Go", "HTML5");
    }

    @Test
    void wordsAndNameTokens() {
        ProfileFacts f = facts();
        assertThat(f.words("E1.H1")).contains("latency", "daily", "users", "senior", "backend", "engineer", "acme",
                "java", "kafka").doesNotContain("pipelines");
        assertThat(f.profileWords()).contains("pipelines", "ledger", "stellenbosch");
        assertThat(f.capitalisedAllowed()).as("profile tokens plus the name and location")
                .contains("globex", "acme", "ledger", "zoë", "ndlovu", "müller", "cape", "town", "github");
    }

    @Test
    void theHeaderComesFromTheProfile() {
        assertThat(facts().header()).isEqualTo(new Header(NAME, EMAIL, PHONE, LOCATION,
                List.of(new LinkItem("GitHub", GITHUB))));
    }

    @Test
    void theBudgetKeepsEveryHeaderAndAddsHighlightsNewestFirst() {
        ProfileFacts full = facts();
        int all = full.promptBlock().length();
        // leave out exactly E2.H2, P1.H1 and P2.H1 (the last three in priority order)
        int budget = all - line("E2.H2", E2H2) - line("P1.H1", P1H1) - line("P2.H1", P2H1);
        ProfileFacts f = ProfileFacts.of(profile(), budget, check(), TODAY);
        assertThat(f.truncated()).isTrue();
        assertThat(f.promptBlock().length()).isLessThanOrEqualTo(budget);
        assertThat(f.promptBlock()).contains("[E1.H1]", "[E1.H2]", "[E1.H3]", "[E2.H1]")
                .doesNotContain("[E2.H2]", "[P1.H1]", "[P2.H1]")
                .contains("[E1] ", "[E2] ", "[P1] ", "[P2] ", "[C1] ", "[D1] ");
        assertThat(f.highlight("E2.H1").shown()).isTrue();
        assertThat(f.highlight("E2.H2").shown()).isFalse();
        assertThat(f.highlight("P1.H1").shown()).isFalse();
        assertThat(f.highlight("E2.H2").text()).as("still known, just not shown").isEqualTo(E2H2);

        ProfileFacts one = ProfileFacts.of(profile(), budget - 1, check(), TODAY);
        assertThat(one.highlight("E2.H1").shown()).isFalse();

        ProfileFacts tiny = ProfileFacts.of(profile(), 10, check(), TODAY);
        assertThat(tiny.truncated()).isTrue();
        assertThat(tiny.promptBlock()).as("headers are always included, even over the budget")
                .contains("[E1] ", "[E2] ", "[P1] ", "[C1] ", "[D1] ").doesNotContain(".H1]");
        assertThat(full.promptBlock().length()).isEqualTo(ProfileFacts.of(profile(), all, check(), TODAY)
                .promptBlock().length());
        assertThat(ProfileFacts.of(profile(), all, check(), TODAY).truncated()).isFalse();
    }

    /** The prompt line of a highlight plus its line break. */
    private static int line(String ref, String text) {
        return ("  [" + ref + "] <profile_text>" + text + "</profile_text>").length() + 1;
    }

    @Test
    void invalidArguments() {
        assertThatThrownBy(() -> ProfileFacts.of(null, 100, check(), TODAY))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ProfileFacts.of(profile(), 0, check(), TODAY))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
