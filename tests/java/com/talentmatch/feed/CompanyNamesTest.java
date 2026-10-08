package com.talentmatch.feed;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** §4.4 company key. */
class CompanyNamesTest {

    @Test
    void legalSuffixesAndCaseCollapseToOneKey() {
        assertThat(CompanyNames.key("Acme (Pty) Ltd")).isEqualTo("acme");
        assertThat(CompanyNames.key("ACME")).isEqualTo("acme");
        assertThat(CompanyNames.key("Acme Inc.")).isEqualTo("acme");
        assertThat(CompanyNames.key("Acme Co.")).isEqualTo("acme");
        assertThat(CompanyNames.key("  Acme   GmbH ")).isEqualTo("acme");
    }

    @Test
    void dottedSuffixDroppedAndApostrophesRemoved() {
        assertThat(CompanyNames.key("Booking B.V.")).isEqualTo("booking");
        assertThat(CompanyNames.key("Macy's Inc")).isEqualTo("macys");
    }

    @Test
    void lastRemainingWordIsNeverDropped() {
        assertThat(CompanyNames.key("Co")).isEqualTo("co");
        assertThat(CompanyNames.key("Co.")).isEqualTo("co");
        assertThat(CompanyNames.key("Ltd Inc")).isEqualTo("ltd");
    }

    @Test
    void suffixInsideTheNameIsKept() {
        assertThat(CompanyNames.key("Co Pilot Labs")).isEqualTo("co pilot labs");
        assertThat(CompanyNames.key("Acme Payments")).isNotEqualTo(CompanyNames.key("Acme"));
    }

    @Test
    void blankGivesEmpty() {
        assertThat(CompanyNames.key(null)).isEmpty();
        assertThat(CompanyNames.key("")).isEmpty();
        assertThat(CompanyNames.key("   ")).isEmpty();
        assertThat(CompanyNames.key("...")).isEmpty();
    }
}
