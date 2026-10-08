package com.talentmatch.feed.source;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/** Probe findings §4.2.3 (Ashby addressCountry is free text). */
class CountryNamesTest {

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(delimiter = '|', value = {
            "USA|US", "US|US", "us|US", "United States|US", "United States of America|US", "U.S.A.|US",
            "UK|GB", "United Kingdom|GB", "GB|GB",
            "Germany|DE", "germany|DE", "DE|DE",
            "South Korea|KR", "Republic of Korea|KR",
            "South Africa|ZA", "ZA|ZA",
            "Canada|CA", "France|FR", "Ireland|IE", "India|IN",
            "  Netherlands  |NL", "The Netherlands|NL",
            "Côte d’Ivoire|CI", "Cote d'Ivoire|CI", "Türkiye|TR"})
    void known(String text, String code) {
        assertThat(CountryNames.toIso2(text)).isEqualTo(code);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "European Union", "EMEA", "EU", "Remote", "Atlantis", "XX", "New York City"})
    void unknownOrRegionIsNull(String text) {
        assertThat(CountryNames.toIso2(text)).isNull();
    }
}
