package com.talentmatch.application.tailoring;

import static com.talentmatch.application.tailoring.TailoringFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

import com.talentmatch.application.content.CoverLetterContent;
import com.talentmatch.application.content.Finding;
import com.talentmatch.application.content.FindingCode;
import com.talentmatch.application.content.Severity;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

/** §9.1.7: fixed parts, BLOCKING checks per paragraph, heuristics, rejection bounds and the notice period. */
class CoverLetterAssemblerTest {

    /** 13 words. */
    static final String G0 = "I am writing to apply for the Senior Java Developer role at Initech.";
    /** 18 words. */
    static final String G1 = "At Acme Ltd I cut API latency by 40% for 12,000 daily users, working with Java and PostgreSQL.";
    /** 18 words: a role duration (4) and a year written in the profile text (2018). */
    static final String G2 = "Before that I spent 4 years at Globex building Python pipelines, and I have used Docker "
            + "since 2018.";
    /** 13 words: a contact detail, a number, a year and a skill that are not in the profile. */
    static final String BAD = "I led 30 engineers with Kubernetes in 2019; reach me at zoe@example.com today.";
    static final String BAD2 = "I also ran Terraform for 25 teams.";

    private static CoverLetterAssembler.Options options() {
        return new CoverLetterAssembler.Options(TODAY, false, null, 20, 100);
    }

    private static AssemblyResult<CoverLetterContent> run(CoverLetterAssembler.Options o, String... paragraphs) {
        return CoverLetterAssembler.assemble(new CoverLetterDraft(Arrays.asList(paragraphs)), facts(), job(), check(),
                o);
    }

    private static AssemblyResult.Assembled<CoverLetterContent> assembled(AssemblyResult<CoverLetterContent> r) {
        assertThat(r).isInstanceOf(AssemblyResult.Assembled.class);
        return (AssemblyResult.Assembled<CoverLetterContent>) r;
    }

    private static AssemblyResult.Assembled<CoverLetterContent> assembled(String... paragraphs) {
        return assembled(run(options(), paragraphs));
    }

    private static String rejected(AssemblyResult<CoverLetterContent> r) {
        assertThat(r).isInstanceOf(AssemblyResult.Rejected.class);
        return ((AssemblyResult.Rejected<CoverLetterContent>) r).reason();
    }

    @Test
    void aGroundedLetterHasNoFindingsAndFixedParts() {
        var a = assembled(G0, G1, G2);
        assertThat(a.findings()).isEmpty();
        CoverLetterContent c = a.content();
        assertThat(c.header()).isEqualTo(facts().header());
        assertThat(c.company()).isEqualTo("Initech");
        assertThat(c.jobTitle()).isEqualTo("Senior Java Developer");
        assertThat(c.date()).isEqualTo(TODAY);
        assertThat(c.salutation()).isEqualTo("Dear Hiring Manager,");
        assertThat(c.closing()).isEqualTo("Kind regards,");
        assertThat(c.signature()).isEqualTo(NAME);
        assertThat(c.paragraphs()).containsExactly(G0, G1, G2);
    }

    @Test
    void paragraphsAreCleanedAndBlankOnesSkipped() {
        var a = assembled("  I am writing\n\nto apply   for the Senior Java Developer role at Initech. ", "", null, "  ",
                G1, G2);
        assertThat(a.content().paragraphs()).containsExactly(G0, G1, G2);
    }

    @Test
    void eachFailedCheckIsBlockingAndNamesTheTokenOnly() {
        var a = assembled(G0, G1, G2, BAD);
        assertThat(a.content().paragraphs()).as("the paragraph is kept").containsExactly(G0, G1, G2, BAD);
        assertThat(a.findings()).extracting(Finding::severity, Finding::code, Finding::path, Finding::value)
                .containsExactly(
                        tuple(Severity.BLOCKING, FindingCode.CONTACT_DETAILS, "paragraphs[3]", "zoe@example.com"),
                        tuple(Severity.BLOCKING, FindingCode.NUMBER_NOT_IN_PROFILE, "paragraphs[3]", "30"),
                        tuple(Severity.BLOCKING, FindingCode.YEAR_NOT_IN_PROFILE, "paragraphs[3]", "2019"),
                        tuple(Severity.BLOCKING, FindingCode.SKILL_NOT_IN_PROFILE, "paragraphs[3]", "Kubernetes"));
        assertThat(a.findings()).extracting(Finding::message).containsExactly(
                "Contact details don't belong in the letter body; your header already has them.",
                "The number 30 is not in your profile. Check it before sending.",
                "The year 2019 is not a date in your profile. Check it before sending.",
                "'Kubernetes' is not in your profile's skills, roles or highlights. Check it before sending.");
    }

    @Test
    void numberWordsAreCheckedButOneIsNot() {
        var a = assembled(G0, G1, "One lesson from leading five engineers: eleven releases taught me patience.");
        assertThat(a.findings()).extracting(Finding::code, Finding::value)
                .containsExactly(tuple(FindingCode.NUMBER_NOT_IN_PROFILE, "eleven"));
    }

    @Test
    void careerSpanRoleDurationsAndJobNumbersAreAllowed() {
        var a = assembled(G0, "I bring 10 years of backend work, 5 of them at Acme Ltd and 4 at Globex.", G2);
        assertThat(a.findings()).isEmpty();
        JobFacts studio = new JobFacts("Java Developer", "Studio 54", null, List.of(), List.of());
        var b = assembled(CoverLetterAssembler.assemble(new CoverLetterDraft(List.of(
                "I would love to join Studio 54 as a Java Developer.", G1, G2)), facts(), studio, check(), options()));
        assertThat(b.findings()).isEmpty();
    }

    @Test
    void yearsMustBeDateYearsOrYearsWrittenInTheProfile() {
        var a = assembled(G0, G1, "I joined Globex in 2016 and Acme Ltd in 2021, and used Docker from 2018 to 2017.");
        assertThat(a.findings()).extracting(Finding::code, Finding::value)
                .containsExactly(tuple(FindingCode.YEAR_NOT_IN_PROFILE, "2017"));
    }

    @Test
    void aPhoneNumberIsBlockingButARunOfProfileYearsIsNot() {
        var a = assembled(G0, G1, "Call me on 082 555 0199 to talk about 2016 2020 2021 at Globex and Acme Ltd.");
        assertThat(a.findings()).extracting(Finding::code, Finding::value)
                .containsExactly(tuple(FindingCode.CONTACT_DETAILS, "082 555 0199"));
    }

    @Test
    void unknownNamesAreWarnings() {
        var a = assembled(G0, G1, "Working with Maria, I shipped an Initech-style rollout on AWS. I hear Peter "
                + "Gibbons leads the team in Cape Town.");
        assertThat(a.findings()).singleElement().satisfies(f -> {
            assertThat(f.severity()).isEqualTo(Severity.WARNING);
            assertThat(f.code()).isEqualTo(FindingCode.UNKNOWN_NAME);
            assertThat(f.value()).isEqualTo("Maria");
            assertThat(f.path()).isEqualTo("paragraphs[2]");
        });
    }

    @Test
    void lengthOutsideTheRangeIsAWarning() {
        var shortLetter = assembled(run(options(), G0));
        assertThat(shortLetter.findings()).singleElement().satisfies(f -> {
            assertThat(f.code()).isEqualTo(FindingCode.LENGTH);
            assertThat(f.severity()).isEqualTo(Severity.WARNING);
            assertThat(f.value()).isEqualTo("13");
            assertThat(f.message()).isEqualTo("The letter has 13 words; 20 to 100 reads best.");
        });
        var longLetter = assembled(run(new CoverLetterAssembler.Options(TODAY, false, null, 20, 40), G0, G1, G2));
        assertThat(longLetter.findings()).extracting(Finding::code, Finding::value)
                .containsExactly(tuple(FindingCode.LENGTH, "49"));
    }

    @Test
    void tooFewOrTooManyWordsAreRejected() {
        String nine = "I would like this Java role at Initech please.";
        assertThat(CoverLetterAssembler.words(List.of(nine))).isEqualTo(9);
        assertThat(rejected(run(options(), nine))).isEqualTo("9 words");
        String ten = "I would like this Java role at Initech, thank you.";
        assertThat(CoverLetterAssembler.words(List.of(ten))).isEqualTo(10);
        assertThat(run(options(), ten)).as("10 = min/2 is allowed").isInstanceOf(AssemblyResult.Assembled.class);

        String word = "Java ";
        assertThat(run(options(), word.repeat(150).strip())).as("150 = max * 1.5 is allowed")
                .isInstanceOf(AssemblyResult.Assembled.class);
        assertThat(rejected(run(options(), word.repeat(151).strip()))).isEqualTo("151 words");
    }

    @Test
    void moreThanHalfTheParagraphsBlockedIsRejected() {
        assertThat(rejected(run(options(), G0, BAD, BAD2))).isEqualTo("2 of 3 paragraphs not grounded");
        var half = assembled(G0, G1, BAD, BAD2);
        assertThat(half.findings()).extracting(Finding::path).contains("paragraphs[2]", "paragraphs[3]");
        assertThat(half.findings()).filteredOn(f -> f.path().equals("paragraphs[3]"))
                .extracting(Finding::code, Finding::value).containsExactly(
                        tuple(FindingCode.NUMBER_NOT_IN_PROFILE, "25"),
                        tuple(FindingCode.SKILL_NOT_IN_PROFILE, "Terraform"));
    }

    @Test
    void anUnavailableVocabularyFlagsEveryParagraphButDoesNotReject() {
        ProfileFacts f = ProfileFacts.of(profile(), 12000, FactCheck.unavailable(), TODAY);
        var a = assembled(CoverLetterAssembler.assemble(new CoverLetterDraft(List.of(G0, G1, G2)), f, job(),
                FactCheck.unavailable(), options()));
        assertThat(a.findings()).extracting(Finding::severity, Finding::code, Finding::path, Finding::value)
                .containsExactly(
                        tuple(Severity.BLOCKING, FindingCode.SKILL_NOT_IN_PROFILE, "paragraphs[0]",
                                FactCheck.UNCHECKED_SKILLS),
                        tuple(Severity.BLOCKING, FindingCode.SKILL_NOT_IN_PROFILE, "paragraphs[1]",
                                FactCheck.UNCHECKED_SKILLS),
                        tuple(Severity.BLOCKING, FindingCode.SKILL_NOT_IN_PROFILE, "paragraphs[2]",
                                FactCheck.UNCHECKED_SKILLS));
        assertThat(a.findings().get(0).message()).contains("couldn't be checked");
    }

    @Test
    void atMostSixParagraphsOfAtMost1500Characters() {
        var six = assembled(G0, G0, G0, G0, G0, G0, "Seventh paragraph here.", "Eighth paragraph here.");
        assertThat(six.content().paragraphs()).hasSize(6).containsOnly(G0);

        String longParagraph = "Java ".repeat(400).strip();
        var cut = assembled(run(new CoverLetterAssembler.Options(TODAY, false, null, 20, 1000), longParagraph));
        assertThat(cut.content().paragraphs().get(0).length()).isLessThanOrEqualTo(1500);
        assertThat(cut.content().paragraphs().get(0)).doesNotEndWith(" ");
    }

    @Test
    void theNoticePeriodSentenceIsAppendedAsItsOwnParagraph() {
        var a = assembled(run(new CoverLetterAssembler.Options(TODAY, true, 30, 20, 100), G0, G1, G2));
        assertThat(a.content().paragraphs()).containsExactly(G0, G1, G2,
                "I am available to start after a notice period of 30 days.");
        assertThat(CoverLetterAssembler.noticeSentence(30))
                .isEqualTo("I am available to start after a notice period of 30 days.");
        assertThat(a.findings()).as("30 is not checked: the server wrote it").isEmpty();
    }

    @Test
    void anUnknownNoticePeriodIsAnInfoFinding() {
        for (Integer days : new Integer[] {null, 0}) {
            var a = assembled(run(new CoverLetterAssembler.Options(TODAY, true, days, 20, 100), G0, G1, G2));
            assertThat(a.content().paragraphs()).containsExactly(G0, G1, G2);
            assertThat(a.findings()).singleElement().satisfies(f -> {
                assertThat(f.code()).isEqualTo(FindingCode.NOTICE_PERIOD_UNKNOWN);
                assertThat(f.severity()).isEqualTo(Severity.INFO);
            });
        }
        var off = assembled(run(new CoverLetterAssembler.Options(TODAY, false, 30, 20, 100), G0, G1, G2));
        assertThat(off.content().paragraphs()).containsExactly(G0, G1, G2);
        assertThat(off.findings()).isEmpty();
    }

    @Test
    void noParagraphsIsRejected() {
        assertThat(rejected(CoverLetterAssembler.assemble(null, facts(), job(), check(), options())))
                .isEqualTo("no paragraphs");
        assertThat(rejected(CoverLetterAssembler.assemble(new CoverLetterDraft(null), facts(), job(), check(),
                options()))).isEqualTo("no paragraphs");
        assertThat(rejected(CoverLetterAssembler.assemble(new CoverLetterDraft(List.of()), facts(), job(), check(),
                options()))).isEqualTo("no paragraphs");
        List<String> blanks = new ArrayList<>(Collections.nCopies(3, " "));
        blanks.add(null);
        assertThat(rejected(CoverLetterAssembler.assemble(new CoverLetterDraft(blanks), facts(), job(), check(),
                options()))).isEqualTo("no paragraphs");
    }

    @Test
    void aMissingJobIsTolerated() {
        var a = assembled(CoverLetterAssembler.assemble(new CoverLetterDraft(List.of(G1, G2)), facts(), null, null,
                options()));
        assertThat(a.content().company()).isNull();
        assertThat(a.content().jobTitle()).isNull();
    }

    /** Phase 7 step 5 fix: a job title's "2.0" is one number; its parts are not allowed on their own. */
    @Test
    void aJobTitleVersionNumberDoesNotAllowItsParts() {
        // E1 only: the fixture's E2 ("2 million rows") would make 2 a profile number
        ProfileFacts e1Only = facts(withRoles(e1()));
        assertThat(e1Only.numbersGlobal()).doesNotContain("2", "0", "2.0");
        JobFacts versioned = new JobFacts("Engineer 2.0", "Initech", null, List.of(), List.of());
        var a = assembled(CoverLetterAssembler.assemble(new CoverLetterDraft(List.of(
                        "I am applying for the Engineer 2.0 role at Initech.",
                        "At Acme Ltd I cut API latency by 40% for 12,000 daily users.",
                        "I ran 2 services with 0 incidents.")), e1Only, versioned, check(), options()));
        assertThat(a.findings()).extracting(Finding::code, Finding::path, Finding::value).containsExactly(
                tuple(FindingCode.NUMBER_NOT_IN_PROFILE, "paragraphs[2]", "2"),
                tuple(FindingCode.NUMBER_NOT_IN_PROFILE, "paragraphs[2]", "0"));
    }
}
