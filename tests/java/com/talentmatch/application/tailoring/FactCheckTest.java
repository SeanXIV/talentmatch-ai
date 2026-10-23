package com.talentmatch.application.tailoring;

import static com.talentmatch.application.tailoring.TailoringFixtures.check;
import static com.talentmatch.application.tailoring.TailoringFixtures.id;
import static com.talentmatch.application.tailoring.TailoringFixtures.snapshot;
import static org.assertj.core.api.Assertions.assertThat;

import com.talentmatch.application.content.FindingCode;
import com.talentmatch.application.tailoring.FactCheck.Problem;
import com.talentmatch.feed.skills.SkillDictionary;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** §9.1.5: number canonicalisation, years, skills (aliases, spans, ambiguity), contact details, fail closed. */
class FactCheckTest {

    private final FactCheck fc = check();

    private List<Problem> numbers(String text, String... allowed) {
        return fc.check(text, Set.of(allowed), Set.of(), null);
    }

    private static Problem number(String token) {
        return new Problem(FindingCode.NUMBER_NOT_IN_PROFILE, token);
    }

    // ------------------------------------------------------------------ numbers

    @Test
    void thousandsSeparatorsAreCanonical() {
        assertThat(numbers("Served 10,000 users", "10000")).isEmpty();
        assertThat(numbers("Served 10000 users", "10000")).isEmpty();
        assertThat(numbers("Served 1,200,000 users", "1200000")).isEmpty();
        assertThat(numbers("Served 10,001 users", "10000")).containsExactly(number("10,001"));
    }

    @Test
    void percentAndPerCentAreTheSameNumber() {
        assertThat(numbers("Cut costs by 40%", "40")).isEmpty();
        assertThat(numbers("Cut costs by 40 per cent", "40")).isEmpty();
        assertThat(numbers("Cut costs by 40 percent", "40")).isEmpty();
        assertThat(numbers("Cut costs by 45%", "40")).as("the token keeps its %").containsExactly(number("45%"));
    }

    @Test
    void aPlusSuffixIsTheBareNumber() {
        assertThat(numbers("5+ years of backend work", "5")).isEmpty();
        assertThat(numbers("6+ years", "5")).containsExactly(number("6"));
    }

    @Test
    void noRoundingOrUnitConversion() {
        assertThat(numbers("Saved $1.2M a year", "1200000")).containsExactly(number("1.2"));
        assertThat(numbers("Saved $1,200,000 a year", "1.2")).containsExactly(number("1,200,000"));
        assertThat(numbers("Saved $1.2M a year", "1.2")).isEmpty();
        assertThat(numbers("about 1.5 times", "1.5")).isEmpty();
        assertThat(numbers("about 2 times", "1.5")).containsExactly(number("2"));
    }

    @Test
    void numberWordsTwoToTwentyCountAsDigits() {
        assertThat(numbers("Led five engineers", "5")).isEmpty();
        assertThat(numbers("Led Five engineers", "5")).isEmpty();
        assertThat(numbers("Led five engineers", "6")).containsExactly(number("five"));
        assertThat(numbers("two teams", "2")).isEmpty();
        assertThat(numbers("two teams")).containsExactly(number("two"));
        assertThat(numbers("thirteen services")).as("13 was not in the old map").containsExactly(number("thirteen"));
        assertThat(numbers("thirteen services", "13")).isEmpty();
        assertThat(numbers("sixteen services")).containsExactly(number("sixteen"));
        assertThat(numbers("twenty services")).containsExactly(number("twenty"));
        assertThat(numbers("twenty services", "20")).isEmpty();
        assertThat(numbers("a hundred services")).as("only one..twenty are number words").isEmpty();
    }

    @Test
    void oneIsNotTreatedAsANumber() {
        assertThat(numbers("One of the team, I own one service and the one-off scripts")).isEmpty();
        assertThat(numbers("I own 1 service")).containsExactly(number("1"));
    }

    // ------------------------------------------------------------------ years

    @Test
    void withAllowedYearsAFourDigitYearMustBeAProfileYear() {
        Set<String> years = Set.of("2019", "2021");
        assertThat(fc.check("Since 2019 I have", Set.of(), Set.of(), years)).isEmpty();
        assertThat(fc.check("Since 2018 I have", Set.of("2018"), Set.of(), years))
                .as("an allowed number is not an allowed year")
                .containsExactly(new Problem(FindingCode.YEAR_NOT_IN_PROFILE, "2018"));
        assertThat(fc.check("In 1999 and 2099", Set.of(), Set.of(), years)).containsExactly(
                new Problem(FindingCode.YEAR_NOT_IN_PROFILE, "1999"),
                new Problem(FindingCode.YEAR_NOT_IN_PROFILE, "2099"));
        assertThat(fc.check("Served 2500 users", Set.of("2500"), Set.of(), years))
                .as("2500 is not a 19xx/20xx year").isEmpty();
        assertThat(fc.check("Served 1850 users", Set.of(), Set.of(), years))
                .containsExactly(number("1850"));
    }

    @Test
    void withoutAllowedYearsYearsAreOrdinaryNumbers() {
        assertThat(numbers("Since 2018", "2018")).isEmpty();
        assertThat(numbers("Since 2018", "2019")).containsExactly(number("2018"));
    }

    // ------------------------------------------------------------------ skills

    @Test
    void aSkillOutsideTheAllowedSetIsReportedAsWritten() {
        assertThat(fc.check("Java and React", Set.of(), Set.of(id("Java")), null))
                .containsExactly(new Problem(FindingCode.SKILL_NOT_IN_PROFILE, "React"));
        assertThat(fc.check("java and react", Set.of(), Set.of(id("Java")), null))
                .containsExactly(new Problem(FindingCode.SKILL_NOT_IN_PROFILE, "react"));
        assertThat(fc.check("Java and React", Set.of(), Set.of(id("Java"), id("React")), null)).isEmpty();
    }

    @Test
    void anAliasOfAnAllowedSkillIsAllowed() {
        assertThat(fc.check("Tuned postgres queries", Set.of(), Set.of(id("PostgreSQL")), null)).isEmpty();
        assertThat(fc.check("Tuned postgres queries", Set.of(), Set.of(id("Java")), null))
                .containsExactly(new Problem(FindingCode.SKILL_NOT_IN_PROFILE, "postgres"));
    }

    @Test
    void anAmbiguousNameNeedsTechContext() {
        assertThat(fc.check("I am ready to go and Go-getting", Set.of(), Set.of(), null))
                .as("'ready to go' with Go absent").isEmpty();
        assertThat(fc.check("I am ready to Go.", Set.of(), Set.of(), null)).isEmpty();
        assertThat(fc.check("Experience with Go services", Set.of(), Set.of(), null))
                .containsExactly(new Problem(FindingCode.SKILL_NOT_IN_PROFILE, "Go"));
    }

    @Test
    void aShorterSkillInsideALongerAllowedSkillDoesNotCount() {
        assertThat(fc.check("Built Spring Boot services", Set.of(), Set.of(id("Spring Boot")), null)).isEmpty();
        assertThat(fc.check("Built Spring and Spring Boot services", Set.of(), Set.of(id("Spring Boot")), null))
                .containsExactly(new Problem(FindingCode.SKILL_NOT_IN_PROFILE, "Spring"));
    }

    @Test
    void digitsInsideAnAllowedSkillMentionAreExemptBySpan() {
        assertThat(fc.check("Built pages in HTML5", Set.of(), Set.of(id("HTML5")), null)).isEmpty();
        assertThat(fc.check("Ran k8s clusters", Set.of(), Set.of(id("Kubernetes")), null)).isEmpty();
        assertThat(fc.check("Built pages in HTML5", Set.of(), Set.of(), null)).containsExactly(number("5"),
                new Problem(FindingCode.SKILL_NOT_IN_PROFILE, "HTML5"));
        assertThat(fc.check("HTML5 and 5 more", Set.of(), Set.of(id("HTML5")), null))
                .as("a stand-alone 5 is still checked").containsExactly(number("5"));
    }

    // ------------------------------------------------------------------ contact details

    @Test
    void contactDetailsAreDetectedAndTheirDigitsAreNotNumbers() {
        assertThat(fc.check("Mail zoe.nm+jobs@example.co.za today", Set.of(), Set.of(), null))
                .containsExactly(new Problem(FindingCode.CONTACT_DETAILS, "zoe.nm+jobs@example.co.za"));
        assertThat(fc.check("See https://github.com/zoe.", Set.of(), Set.of(), null))
                .containsExactly(new Problem(FindingCode.CONTACT_DETAILS, "https://github.com/zoe"));
        assertThat(fc.check("See www.zoe.dev for more", Set.of(), Set.of(), null))
                .containsExactly(new Problem(FindingCode.CONTACT_DETAILS, "www.zoe.dev"));
        assertThat(fc.check("Call +27 82 555 0199 now", Set.of(), Set.of(), null))
                .containsExactly(new Problem(FindingCode.CONTACT_DETAILS, "+27 82 555 0199"));
        assertThat(fc.check("Call (082) 555-0199", Set.of(), Set.of(), null))
                .containsExactly(new Problem(FindingCode.CONTACT_DETAILS, "(082) 555-0199"));
        assertThat(fc.contactDetails("zoe@example.com or 0825550199")).containsExactly("zoe@example.com",
                "0825550199");
    }

    @Test
    void aPhoneHasNineToFifteenDigits() {
        assertThat(fc.contactDetails("ref 1234 5678")).as("8 digits").isEmpty();
        assertThat(fc.contactDetails("ref 123 456 789")).as("9 digits").containsExactly("123 456 789");
        assertThat(fc.contactDetails("ref 123456789012345")).as("15 digits").containsExactly("123456789012345");
        assertThat(fc.contactDetails("ref 1234567890123456")).as("16 digits").isEmpty();
        assertThat(numbers("ref 1234 5678")).containsExactly(number("1234"), number("5678"));
    }

    @Test
    void aRunOfYearsIsNotAPhoneNumber() {
        assertThat(fc.contactDetails("in 2019 2020 2021")).isEmpty();
        assertThat(fc.check("in 2019 2020 2021", Set.of(), Set.of(), Set.of("2019", "2020", "2021"))).isEmpty();
        assertThat(fc.contactDetails("in 2019 2020 1234")).as("not all years").containsExactly("2019 2020 1234");
    }

    @Test
    void problemsComeInOrderContactNumbersSkills() {
        List<Problem> p = fc.check("React at 30 places, zoe@example.com", Set.of(), Set.of(), null);
        assertThat(p).containsExactly(new Problem(FindingCode.CONTACT_DETAILS, "zoe@example.com"), number("30"),
                new Problem(FindingCode.SKILL_NOT_IN_PROFILE, "React"));
        assertThat(fc.check("Led 3 teams, then 3 more", Set.of(), Set.of(), null)).as("repeats reported once")
                .containsExactly(number("3"));
        assertThat(fc.check(null, Set.of(), Set.of(), null)).isEmpty();
        assertThat(fc.check("  ", null, null, null)).isEmpty();
    }

    // ------------------------------------------------------------------ fail closed

    @Test
    void withoutAVocabularyEverySkillCheckFailsClosed() {
        FactCheck none = FactCheck.unavailable();
        assertThat(none.available()).isFalse();
        assertThat(none.vocabulary()).isNull();
        assertThat(none.check("Plain words only", Set.of(), Set.of(), null))
                .containsExactly(new Problem(FindingCode.SKILL_NOT_IN_PROFILE, FactCheck.UNCHECKED_SKILLS));
        assertThat(none.check("Java", Set.of(), Set.of(UUID.randomUUID()), null))
                .containsExactly(new Problem(FindingCode.SKILL_NOT_IN_PROFILE, FactCheck.UNCHECKED_SKILLS));
        assertThat(none.check("", Set.of(), Set.of(), null)).isEmpty();
        assertThat(none.mentions("Java")).isEmpty();
        assertThat(none.resolve("Java")).isEmpty();
        assertThat(none.check("Led 9 people", Set.of(), Set.of(), null)).contains(number("9"));
    }

    @Test
    void loadFailsClosed() {
        assertThat(FactCheck.load(() -> {
            throw new IllegalStateException("db down");
        }).available()).isFalse();
        assertThat(FactCheck.load(() -> null).available()).isFalse();
        assertThat(FactCheck.load(null).available()).isFalse();
        assertThat(FactCheck.load(() -> new SkillDictionary.Snapshot("fp", java.util.Map.of(), java.util.Map.of(),
                null)).available()).as("a snapshot without a matcher").isFalse();
        assertThat(new FactCheck(null).available()).isFalse();
        FactCheck loaded = FactCheck.load(TailoringFixtures::snapshot);
        assertThat(loaded.available()).isTrue();
        assertThat(loaded.resolve("K8S")).contains(id("Kubernetes"));
        assertThat(loaded.mentions("Java and postgres")).containsExactlyInAnyOrder(id("Java"), id("PostgreSQL"));
        assertThat(new FactCheck(snapshot()).available()).isTrue();
    }

    // ------------------------------------------------------------------ names and overlap

    @Test
    void unknownNamesSkipSentenceStartsAcronymsStopWordsVocabularyAndAllowedTokens() {
        String text = "I worked with Maria at Initech on NASA systems. Then Bob joined in March, using Kafka. "
                + "\"Quality\" matters.\nNew lines start sentences.";
        assertThat(fc.unknownNames(text, Set.of())).containsExactly("Maria", "Initech", "Bob");
        assertThat(fc.unknownNames(text, Set.of("initech", "maria"))).containsExactly("Bob");
        assertThat(fc.unknownNames("Dear Sam, on Monday I met Lee", Set.of())).containsExactly("Sam", "Lee");
        assertThat(fc.unknownNames(null, Set.of())).isEmpty();
        assertThat(FactCheck.nameTokens("Acme Ltd, Zoë")).containsExactlyInAnyOrder("acme", "ltd", "zoë");
    }

    @Test
    void overlapIsTheShareOfContentWordsFromTheSources() {
        assertThat(FactCheck.overlap("Reduced latency for users", Set.of("reduced", "latency", "users")))
                .isEqualTo(1.0);
        assertThat(FactCheck.overlap("Reduced latency massively everywhere", Set.of("reduced", "latency")))
                .isEqualTo(0.5);
        assertThat(FactCheck.overlap("a an to of", Set.of())).as("no content words").isEqualTo(1.0);
    }
}
