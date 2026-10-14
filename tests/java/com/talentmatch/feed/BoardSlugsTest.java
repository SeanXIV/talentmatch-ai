package com.talentmatch.feed;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/** Step 12: board-name guesses from a company name (pure). */
class BoardSlugsTest {

    private static List<String> c(String name) {
        return BoardSlugs.candidates(name, 3);
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(delimiter = '|', value = {
            "Acme Payments (Pty) Ltd | acmepayments,acme-payments,acme",
            "Acme                    | acme",
            "AT&T                    | att,at-t",
            "Macy's                  | macys",
            "Café Inc.               | cafe",
            "Ltd                     | ltd",
            "ACME Payments           | acmepayments,acme-payments,acme",
            "Acme B.V.               | acme",
            "Acme Payments Co        | acmepayments,acme-payments,acme",
            "Acme Payments GmbH Ltd  | acmepayments,acme-payments,acme",
            "Go Pay                  | gopay,go-pay",
            "Zürich Versicherung AG  | zurichversicherung,zurich-versicherung,zurich",
            "  Acme   Payments  !    | acmepayments,acme-payments,acme",
            "Ben & Jerry’s           | benjerrys,ben-jerrys,ben",
            "3M                      | 3m"})
    void table(String name, String expected) {
        assertThat(c(name)).containsExactly(expected.split(","));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "!!!", "...", "Яндекс", "株式会社", "—", "'''"})
    void nothingToBuildFrom(String name) {
        assertThat(c(name)).isEmpty();
    }

    @Test
    void maxLimitsMostSpecificFirst() {
        String name = "Acme Payments (Pty) Ltd";
        assertThat(BoardSlugs.candidates(name, 1)).containsExactly("acmepayments");
        assertThat(BoardSlugs.candidates(name, 2)).containsExactly("acmepayments", "acme-payments");
        assertThat(BoardSlugs.candidates(name, 5)).containsExactly("acmepayments", "acme-payments", "acme");
        assertThat(BoardSlugs.candidates(name, 0)).isEmpty();
        assertThat(BoardSlugs.candidates(name, -1)).isEmpty();
    }

    @Test
    void longNameDropsJoinedAndHyphenatedFormsOver100Chars() {
        // 11 words of 10 letters: 120 characters; joined 110, hyphenated 120 - both invalid tokens.
        String word = "abcdefghij";
        String name = String.join(" ", java.util.Collections.nCopies(11, word));
        assertThat(name).hasSize(120);
        assertThat(c(name)).containsExactly(word);
        assertThat(c(name)).allSatisfy(s -> assertThat(SourceKeys.isValidToken(s)).isTrue());
    }

    @Test
    void exactly100CharsIsKept() {
        String name = "a".repeat(50) + " " + "b".repeat(50);
        assertThat(c(name)).containsExactly("a".repeat(50) + "b".repeat(50), "a".repeat(50));
    }

    @Test
    void duplicatesRemoved() {
        // a single word gives joined == the only form; "acme acme" gives first word == ... distinct
        assertThat(c("Acme")).containsExactly("acme");
        assertThat(c("Acme Acme")).containsExactly("acmeacme", "acme-acme", "acme").doesNotHaveDuplicates();
        assertThat(c("A-C-M-E")).doesNotHaveDuplicates();
    }

    @Test
    void nfkdAndFullwidth() {
        assertThat(c("ＡＣＭＥ Ｐａｙ")).containsExactly("acmepay", "acme-pay", "acme");
        assertThat(c("ℌello World")).containsExactly("helloworld", "hello-world", "hello");
        assertThat(c("Crème Brûlée Ltd")).containsExactly("cremebrulee", "creme-brulee", "creme");
        assertThat(c("ﬁnance")).containsExactly("finance");
        assertThat(c("Acme①")).containsExactly("acme1");
    }

    @Test
    void mixedScriptKeepsLatinPart() {
        assertThat(c("Яндекс Acme")).containsExactly("acme");
    }

    @Test
    void everyCandidateIsAValidLowerCaseToken() {
        for (String name : List.of("Acme Payments (Pty) Ltd", "AT&T", "Café Inc.", "ＡＣＭＥ", "x_y.z")) {
            assertThat(c(name)).allSatisfy(s -> {
                assertThat(SourceKeys.isValidToken(s)).as(s).isTrue();
                assertThat(s).matches("[a-z0-9-]+");
            });
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"Acme", "3M", "Café", "  !a! ", "Ltd"})
    void hasLettersOrDigitsTrue(String name) {
        assertThat(BoardSlugs.hasLettersOrDigits(name)).isTrue();
    }

    @Test
    void hasLettersOrDigitsTrueForA150CharWordThatHasNoCandidates() {
        String word = "a".repeat(150);
        assertThat(BoardSlugs.hasLettersOrDigits(word)).isTrue();
        assertThat(c(word)).as("over 100 chars: not a valid board token").isEmpty();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "\t\n", "!!!", "...", "Яндекс", "株式会社", "—", "'''"})
    void hasLettersOrDigitsFalse(String name) {
        assertThat(BoardSlugs.hasLettersOrDigits(name)).isFalse();
        assertThat(c(name)).isEmpty();
    }
}
