package com.talentmatch.profile;

import static org.assertj.core.api.Assertions.assertThat;

import com.talentmatch.application.tailoring.FactCheck;
import com.talentmatch.application.tailoring.TailoringFixtures;
import java.util.Set;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/** The shared never-invent text helpers (Phase 7 step 2 extraction). */
class ProfileTextTest {

    @Test
    void numberWordsCoverOneToTwenty() {
        String[] words = {"one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten", "eleven",
                "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen", "twenty"};
        IntStream.rangeClosed(1, 20).forEach(n -> {
            assertThat(ProfileText.numberWord(n)).isEqualTo(words[n - 1]);
            assertThat(ProfileText.numberWordValue(words[n - 1])).isEqualTo(n);
            assertThat(ProfileText.numberWordValue(words[n - 1].toUpperCase())).isEqualTo(n);
        });
        assertThat(ProfileText.numberWord(0)).isNull();
        assertThat(ProfileText.numberWord(21)).isNull();
        assertThat(ProfileText.numberWordValue("hundred")).isNull();
        assertThat(ProfileText.numberWordValue(null)).isNull();
        assertThat(ProfileText.numberWordValues("Thirteen apps, two teams and twenty-one days"))
                .containsExactlyInAnyOrder("13", "2", "20", "1");
    }

    @Test
    void numbersAndCanonicalForms() {
        assertThat(ProfileText.canonicalNumber("10,000")).isEqualTo("10000");
        assertThat(ProfileText.canonicalNumber("1,200,000")).isEqualTo("1200000");
        assertThat(ProfileText.canonicalNumber("1.5")).isEqualTo("1.5");
        assertThat(ProfileText.canonicalNumber("1,5")).as("not a thousands separator").isEqualTo("1,5");
        assertThat(ProfileText.numbers("2019-2021 and 03/2021")).contains("2019", "2021", "03");
        assertThat(ProfileText.numbers("Served 10,000 users at 99.9%")).contains("10000", "10", "000", "99.9", "99",
                "9");
        assertThat(ProfileText.numbers(null)).isEmpty();
    }

    @Test
    void keysAndContentWords() {
        assertThat(ProfileText.key("  Ｊａｖａ   C++, C#!­ ")).isEqualTo("java c++ c#");
        assertThat(ProfileText.key(null)).isEmpty();
        assertThat(ProfileText.contentWords("Led the Kafka migration and ﬁxed it")).containsExactly("kafka",
                "migration", "fixed");
        assertThat(ProfileText.contentWords(null)).isEmpty();
    }

    // ------------------------------------------------------------------ wholeNumbers (Phase 7 step 5 fix)

    @Test
    void wholeNumbersKeepOnlyTheCanonicalWholeToken() {
        assertThat(ProfileText.wholeNumbers("12,000")).containsExactly("12000");
        assertThat(ProfileText.wholeNumbers("1.5")).containsExactly("1.5");
        assertThat(ProfileText.wholeNumbers("1,200,000 and 99.9%")).containsExactlyInAnyOrder("1200000", "99.9");
        assertThat(ProfileText.wholeNumbers("Cut API latency by 40% for 12,000 daily users"))
                .containsExactlyInAnyOrder("40", "12000");
        assertThat(ProfileText.wholeNumbers("Engineer 2.0")).containsExactly("2.0");
        assertThat(ProfileText.wholeNumbers("1,5 litres")).as("not a thousands separator: kept as written")
                .containsExactly("1,5");
        assertThat(ProfileText.wholeNumbers("2019-2021 and 03/2021")).containsExactlyInAnyOrder("2019", "2021", "03");
        assertThat(ProfileText.wholeNumbers("no digits here")).isEmpty();
        assertThat(ProfileText.wholeNumbers(null)).isEmpty();
    }

    @Test
    void wholeNumbersAreASubsetOfNumbers() {
        for (String t : new String[] {"12,000", "1.5", "1,200,000", "99.9%", "2.0", "2019-2021", "a 7 b"}) {
            assertThat(ProfileText.numbers(t)).as(t).containsAll(ProfileText.wholeNumbers(t));
        }
    }

    /**
     * The allowed side (wholeNumbers) and the checked side (FactCheck canonicalises each token the same way) agree:
     * a text checked against its own whole numbers has no number problems, and a part alone is still a problem.
     */
    @Test
    void wholeNumbersMatchTheCheckedSidesCanonicalForms() {
        FactCheck check = TailoringFixtures.check();
        for (String t : new String[] {"Served 12,000 users", "Uptime 99.9% over 1.5 years", "Raised 1,200,000 rand",
                "Version 2.0 shipped", "Saved 1,5 litres", "From 03/2021"}) {
            assertThat(check.check(t, ProfileText.wholeNumbers(t), Set.of(), null)).as(t).isEmpty();
        }
        assertThat(check.check("Served 12 users", ProfileText.wholeNumbers("Served 12,000 users"), Set.of(), null))
                .extracting(FactCheck.Problem::value).containsExactly("12");
        assertThat(check.check("Took 1 year", ProfileText.wholeNumbers("Took 1.5 years"), Set.of(), null))
                .extracting(FactCheck.Problem::value).containsExactly("1");
        assertThat(check.check("Version 2 and 0 bugs", ProfileText.wholeNumbers("Version 2.0"), Set.of(), null))
                .extracting(FactCheck.Problem::value).containsExactly("2", "0");
    }
}
