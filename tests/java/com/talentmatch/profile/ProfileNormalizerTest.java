package com.talentmatch.profile;

import static com.talentmatch.profile.ProfileFixtures.exp;
import static com.talentmatch.profile.ProfileFixtures.skill;
import static com.talentmatch.profile.ProfileFixtures.withExperience;
import static com.talentmatch.profile.ProfileFixtures.withSkills;
import static org.assertj.core.api.Assertions.assertThat;

import com.talentmatch.profile.ProfileDocument.Experience;
import com.talentmatch.profile.ProfileDocument.SkillEntry;
import com.talentmatch.profile.ProfileNormalizer.Issue;
import com.talentmatch.profile.ProfileNormalizer.Mode;
import com.talentmatch.profile.ProfileNormalizer.Result;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Phase 4: ProfileNormalizer (test plan; B3 placeholders, S9 output-index paths, N5 clock). */
class ProfileNormalizerTest {

    /** "Today" is 2026-10-05, so the latest accepted year is 2036. */
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-05T12:00:00Z"), ZoneOffset.UTC);

    private static Result lenient(ProfileDocument d) {
        return ProfileNormalizer.normalize(d, Mode.LENIENT, CLOCK);
    }

    private static Result strict(ProfileDocument d) {
        return ProfileNormalizer.normalize(d, Mode.STRICT, CLOCK);
    }

    private static List<String> paths(Result r) {
        List<String> out = new ArrayList<>();
        r.issues().forEach(i -> out.add(i.path()));
        return out;
    }

    // ------------------------------------------------------------------ dates

    @ParameterizedTest
    @CsvSource({
            "2021, 2021",
            "2021-03, 2021-03",
            "2021-3, 2021-03",
            "2021/03, 2021-03",
            "03/2021, 2021-03",
            "3.2021, 2021-03",
            "Sept 2021, 2021-09",
            "September 2021, 2021-09",
            "sep. 2021, 2021-09",
            "'Mar, 2021', 2021-03",
            "1900, 1900",
            "2036, 2036"})
    void acceptedDates(String raw, String expected) {
        Result r = strict(withExperience(exp("Engineer", "Acme", raw, null, false)));
        assertThat(r.issues()).isEmpty();
        assertThat(r.document().experience().get(0).startDate()).isEqualTo(expected);
    }

    @ParameterizedTest
    @ValueSource(strings = {"2021-13", "13/2021", "2021-00", "1800", "1899", "2037", "Smarch 2021", "last year",
            "21", "2021-03-15"})
    void rejectedDates(String raw) {
        Result s = strict(withExperience(exp("Engineer", "Acme", raw, null, false)));
        assertThat(paths(s)).containsExactly("experience[0].startDate");
        assertThat(s.document().experience().get(0).startDate()).as("STRICT keeps the value").isEqualTo(raw);

        Result l = lenient(withExperience(exp("Engineer", "Acme", raw, null, false)));
        assertThat(paths(l)).containsExactly("experience[0].startDate");
        assertThat(l.document().experience().get(0).startDate()).as("LENIENT clears it").isNull();
    }

    @Test
    void futureLimitFollowsTheInjectedClock() {
        Clock in2040 = Clock.fixed(Instant.parse("2040-01-01T00:00:00Z"), ZoneOffset.UTC);
        Result r = ProfileNormalizer.normalize(withExperience(exp("E", "A", "2045", null, false)), Mode.STRICT, in2040);
        assertThat(r.issues()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"Present", "present", "Current", "now", "ongoing", "to date", "Today"})
    void presentEndDateMeansCurrent(String word) {
        Result r = lenient(withExperience(exp("Engineer", "Acme", "2020", word, false)));
        Experience e = r.document().experience().get(0);
        assertThat(e.endDate()).isNull();
        assertThat(e.current()).isTrue();
        assertThat(r.issues()).isEmpty();
        assertThat(strict(withExperience(exp("Engineer", "Acme", "2020", word, null))).document().experience().get(0)
                .current()).isTrue();
    }

    @Test
    void currentWithEndDate() {
        Result l = lenient(withExperience(exp("Engineer", "Acme", "2020", "2022", true)));
        assertThat(l.document().experience().get(0).endDate()).isNull();
        assertThat(paths(l)).containsExactly("experience[0].endDate");

        Result s = strict(withExperience(exp("Engineer", "Acme", "2020", "2022", true)));
        assertThat(paths(s)).containsExactly("experience[0].endDate");
        assertThat(s.document().experience().get(0).endDate()).isEqualTo("2022");
    }

    @Test
    void endBeforeStart() {
        assertThat(paths(strict(withExperience(exp("E", "A", "2022-05", "2021-01", false)))))
                .containsExactly("experience[0].endDate");
        assertThat(paths(strict(withExperience(exp("E", "A", "2022-05", "2022", false)))))
                .as("same year at coarser precision is fine").isEmpty();
        assertThat(paths(strict(withExperience(exp("E", "A", "2022", "2022-01", false))))).isEmpty();
    }

    // ------------------------------------------------------------------ skills

    @Test
    void duplicateSkillsLenientMergesKeepingYears() {
        Result r = lenient(withSkills(skill("Java", null), skill("java", 5), skill(" JAVA ", 7), skill("SQL", null)));
        assertThat(r.document().skills()).extracting(SkillEntry::name).containsExactly("Java", "SQL");
        assertThat(r.document().skills().get(0).years()).as("first stated years kept").isEqualTo(5);
        assertThat(r.issues()).isEmpty();
    }

    @Test
    void duplicateSkillsStrictIsAnIssueAtTheRequestIndex() {
        Result r = strict(withSkills(skill("Java", null), skill("SQL", null), skill("java", 5)));
        assertThat(paths(r)).containsExactly("skills[2].name");
        assertThat(r.issues().get(0).message()).contains("skills[0]");
    }

    @Test
    void skillYearsOutOfRange() {
        Result l = lenient(withSkills(skill("Java", 61), skill("SQL", -1), skill("Git", 60)));
        assertThat(l.document().skills()).extracting(SkillEntry::years).containsExactly(null, null, 60);
        assertThat(paths(l)).containsExactly("skills[0].years", "skills[1].years");
        assertThat(paths(strict(withSkills(skill("Java", 61))))).containsExactly("skills[0].years");
    }

    // ------------------------------------------------------------------ limits and lengths

    @Test
    void listLimitKeepsFirstEntriesWithABarePathIssue() {
        List<SkillEntry> many = new ArrayList<>();
        for (int i = 0; i < ProfileNormalizer.MAX_SKILLS + 5; i++) {
            many.add(skill("Skill" + i, null));
        }
        Result r = lenient(withSkills(many.toArray(SkillEntry[]::new)));
        assertThat(r.document().skills()).hasSize(ProfileNormalizer.MAX_SKILLS);
        assertThat(r.document().skills().get(0).name()).isEqualTo("Skill0");
        assertThat(paths(r)).containsExactly("skills");
        assertThat(paths(strict(withSkills(many.toArray(SkillEntry[]::new))))).contains("skills");
    }

    @Test
    void tooLongTextLenientTruncatesStrictErrors() {
        String longName = "A".repeat(ProfileNormalizer.MAX_NAME + 10);
        ProfileDocument d = ProfileFixtures.doc(longName, null, List.of(), List.of());
        Result l = lenient(d);
        assertThat(l.document().fullName()).hasSize(ProfileNormalizer.MAX_NAME);
        assertThat(paths(l)).containsExactly("fullName");
        assertThat(l.issues().get(0).value().length()).as("issue value is a short preview").isLessThan(70);

        Result s = strict(d);
        assertThat(s.document().fullName()).isEqualTo(longName);
        assertThat(paths(s)).containsExactly("fullName");
    }

    @Test
    void cutNeverSplitsSurrogatePairs() {
        assertThat(ProfileNormalizer.cut("ab😀", 3)).isEqualTo("ab");
        assertThat(ProfileNormalizer.cut("abc", 5)).isEqualTo("abc");
    }

    @Test
    void whitespaceCollapsedAndBlankEntriesDropped() {
        Result r = lenient(new ProfileDocument("  Ada \n Lovelace ", " ADA@Example.com ", null, null, null,
                "Line one.\n\n\n\nLine   two.", List.of(), List.of(exp("  ", null, null, null, null)), List.of(),
                List.of(skill("  ", 3)), List.of(), List.of(), List.of()));
        assertThat(r.document().fullName()).isEqualTo("Ada Lovelace");
        assertThat(r.document().email()).isEqualTo("ada@example.com");
        assertThat(r.document().summary()).isEqualTo("Line one.\n\nLine two.");
        assertThat(r.document().experience()).isEmpty();
        assertThat(r.document().skills()).isEmpty();
    }

    @Test
    void nullListsBecomeEmpty() {
        Result r = lenient(new ProfileDocument("Ada", null, null, null, null, null, null, null, null, null, null,
                null, null));
        assertThat(r.document().links()).isEmpty();
        assertThat(r.document().experience()).isEmpty();
        assertThat(r.document().languages()).isEmpty();
        assertThat(lenient(null).document().fullName()).isNull();
    }

    @Test
    void invalidEmail() {
        ProfileDocument d = ProfileFixtures.doc("Ada", "not-an-email", List.of(), List.of());
        assertThat(lenient(d).document().email()).isNull();
        assertThat(paths(lenient(d))).containsExactly("email");
        assertThat(paths(strict(d))).containsExactly("email");
    }

    @Test
    void strictEmptyEntryIsAnIssue() {
        Result r = strict(withExperience(exp("Engineer", "Acme", null, null, false), exp(" ", null, null, null, null)));
        assertThat(paths(r)).containsExactly("experience[1]");
    }

    // ------------------------------------------------------------------ B3 placeholders

    @ParameterizedTest
    @ValueSource(strings = {"N/A", "n/a", "NA", "None", "null", "Unknown", "Not provided", "not specified",
            "Not stated", "-", "—", "–", "N/A.", "  n/a  "})
    void placeholdersBecomeNullInLenient(String placeholder) {
        ProfileDocument d = new ProfileDocument("Ada Lovelace", placeholder, placeholder, placeholder, placeholder,
                placeholder, List.of(), List.of(exp("Engineer", "Acme", "2020", placeholder, false)), List.of(),
                List.of(skill("Java", null)), List.of(), List.of(), List.of());
        Result r = lenient(d);
        ProfileDocument out = r.document();
        assertThat(out.email()).isNull();
        assertThat(out.phone()).isNull();
        assertThat(out.location()).isNull();
        assertThat(out.headline()).isNull();
        assertThat(out.summary()).isNull();
        assertThat(out.experience().get(0).endDate()).isNull();
        assertThat(r.issues()).as("silently: nothing was there").isEmpty();
    }

    @Test
    void placeholdersAreKeptInStrict() {
        ProfileDocument d = new ProfileDocument("Ada Lovelace", null, "N/A", null, null, null, List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of());
        assertThat(strict(d).document().phone()).isEqualTo("N/A");
    }

    @Test
    void realValuesAreNotPlaceholders() {
        for (String v : List.of("Nan", "Nantes", "Noney", "N/A Corp", "Unknown Pleasures Ltd", "NA Inc")) {
            assertThat(ProfileNormalizer.isPlaceholder(v)).as(v).isFalse();
        }
        assertThat(ProfileNormalizer.isPlaceholder(null)).isFalse();
    }

    // ------------------------------------------------------------------ S9 output-index paths

    @Test
    void issuePathsUseTheOutputIndexAfterADroppedEntry() {
        Result r = lenient(withExperience(
                exp(null, null, null, null, null),               // dropped
                exp("Engineer", "Acme", "2020-01", "bad", false))); // becomes experience[0]
        assertThat(r.document().experience()).hasSize(1);
        assertThat(paths(r)).containsExactly("experience[0].endDate");
    }

    @Test
    void issuesOfADroppedEntryUseTheBareListPath() {
        Result r = lenient(withExperience(
                exp("Engineer", "Acme", "2020-01", null, false),
                new Experience(null, null, null, "garbage-date", null, null, List.of(), List.of())));
        assertThat(r.document().experience()).hasSize(1);
        for (Issue i : r.issues()) {
            assertThat(i.path()).doesNotContain("experience[1]");
        }
    }

    @Test
    void bulletPathsUseTheOutputIndex() {
        Result r = lenient(withExperience(exp("Engineer", "Acme", null, null, false, List.of(),
                List.of("  ", "x".repeat(1001)))));
        assertThat(r.document().experience().get(0).highlights()).hasSize(1);
        assertThat(paths(r)).containsExactly("experience[0].highlights[0]");
    }

    @Test
    void skillIssuesArePointedAtTheMergedEntryAfterDedup() {
        Result r = lenient(withSkills(skill("Java", null), skill("SQL", null), skill("java", 99)));
        // skills[2] (java, 99) is merged into skills[0]; its out-of-range years issue must point at [0]
        assertThat(paths(r)).containsExactly("skills[0].years");
    }

    @Test
    void experienceTechnologiesNormalizedAndDeduplicated() {
        Result r = lenient(withExperience(exp("Engineer", "Acme", null, null, false,
                List.of(" Java ", "java", "PostgreSQL", "  "), List.of())));
        assertThat(r.document().experience().get(0).technologies()).containsExactly("Java", "PostgreSQL");
    }
}
