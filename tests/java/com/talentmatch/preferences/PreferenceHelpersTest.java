package com.talentmatch.preferences;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Spec §9.1 item 11 helpers: TitleMatcher, Seniority.fromTitle, SalaryNormalizer, RemoteEligibility, Gazetteer. */
class PreferenceHelpersTest {

    // ------------------------------------------------------------------ TitleMatcher

    @ParameterizedTest(name = "{0} ~ {1} = {2}")
    @CsvSource(delimiter = '|', value = {
            "Backend Developer       | Senior Back-End Engineer          | true",
            "Backend Engineer        | Back end programmer               | true",
            "back-end developer      | Software Engineer (Backend)       | true",
            "Frontend Engineer       | Front End Developer               | true",
            "Full Stack Developer    | Fullstack Engineer                | true",
            "Senior Java Developer   | Java Engineer                     | true",
            "Sr. Java Developer      | Junior Java Programmer            | true",
            "Data Engineer           | Data Engineering Manager          | false",
            "Backend Engineer        | Frontend Engineer                 | false",
            "C++ Developer           | C++ Engineer                      | true",
            "C++ Developer           | C Engineer                        | false",
            "C# Developer            | .NET / C# Engineer                | true"})
    void titleMatches(String target, String title, boolean expected) {
        assertThat(TitleMatcher.matches(target, title)).isEqualTo(expected);
    }

    @Test
    void anyMatches() {
        assertThat(TitleMatcher.anyMatches(List.of(), "Anything")).isTrue();
        assertThat(TitleMatcher.anyMatches(null, "Anything")).isTrue();
        assertThat(TitleMatcher.anyMatches(List.of("Data Engineer", "Backend Developer"), "Backend Engineer")).isTrue();
        assertThat(TitleMatcher.anyMatches(List.of("Data Engineer"), "Backend Engineer")).isFalse();
        assertThat(TitleMatcher.anyMatches(List.of("Data Engineer"), null)).isFalse();
    }

    @Test
    void containsKeyword() {
        assertThat(TitleMatcher.containsKeyword("Sales Engineer", "sales")).isTrue();
        assertThat(TitleMatcher.containsKeyword("Inside Sales Representative", "Inside Sales")).isTrue();
        assertThat(TitleMatcher.containsKeyword("Sales Inside Representative", "Inside Sales")).isFalse();
        assertThat(TitleMatcher.containsKeyword("International Developer", "Intern")).isFalse();
        assertThat(TitleMatcher.containsKeyword("Salesforce Developer", "Sales")).isFalse();
        assertThat(TitleMatcher.containsKeyword("Developer", " ")).isFalse();
        assertThat(TitleMatcher.containsKeyword(null, "Sales")).isFalse();
    }

    // ------------------------------------------------------------------ Seniority

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(delimiter = '|', value = {
            "Software Engineering Intern        | INTERN",
            "Internship: Data                   | INTERN",
            "Junior Developer                   | JUNIOR",
            "Jr. Developer                      | JUNIOR",
            "Graduate Software Engineer         | JUNIOR",
            "Entry Level Analyst                | JUNIOR",
            "Senior Backend Engineer            | SENIOR",
            "Sr. Backend Engineer               | SENIOR",
            "Senior Engineering Manager         | SENIOR",
            "Team Lead                          | LEAD",
            "Staff Engineer                     | LEAD",
            "Principal Engineer                 | PRINCIPAL",
            "Solutions Architect                | PRINCIPAL",
            "Engineering Manager                | MANAGER",
            "Head of Engineering                | MANAGER",
            "Director of Platform               | MANAGER",
            "Intermediate Java Developer        | MID",
            "Mid-level Developer                | MID",
            "Backend Engineer                   | UNKNOWN",
            "International Sales                | UNKNOWN",
            "Leadership Coach                   | UNKNOWN",
            "Headless CMS Developer             | UNKNOWN"})
    void seniorityFromTitle(String title, Seniority expected) {
        assertThat(Seniority.fromTitle(title)).isEqualTo(expected);
    }

    @Test
    void seniorityOfNothingIsUnknown() {
        assertThat(Seniority.fromTitle(null)).isEqualTo(Seniority.UNKNOWN);
        assertThat(Seniority.fromTitle("  ")).isEqualTo(Seniority.UNKNOWN);
    }

    // ------------------------------------------------------------------ SalaryNormalizer

    @Test
    void annualize() {
        assertThat(SalaryNormalizer.annualize(new BigDecimal("50000"), SalaryPeriod.MONTH))
                .hasValueSatisfying(v -> assertThat(v).isEqualByComparingTo("600000"));
        assertThat(SalaryNormalizer.annualize(new BigDecimal("600000.50"), SalaryPeriod.YEAR))
                .hasValueSatisfying(v -> assertThat(v).isEqualByComparingTo("600000.50"));
        assertThat(SalaryNormalizer.annualize(new BigDecimal("500"), SalaryPeriod.DAY)).isEmpty();
        assertThat(SalaryNormalizer.annualize(new BigDecimal("50"), SalaryPeriod.HOUR)).isEmpty();
        assertThat(SalaryNormalizer.annualize(null, SalaryPeriod.YEAR)).isEmpty();
        assertThat(SalaryNormalizer.annualize(BigDecimal.ONE, null)).isEmpty();
    }

    // ------------------------------------------------------------------ RemoteEligibility / Gazetteer

    private static final List<String> KW = JobPreferences.DEFAULT_REMOTE_LOCATION_KEYWORDS;

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(delimiter = '|', value = {
            "US only                       | NOT_ELIGIBLE",
            "Remote, USA                   | NOT_ELIGIBLE",
            "Remote - United States        | NOT_ELIGIBLE",
            "Remote (UK)                   | NOT_ELIGIBLE",
            "Remote - Europe               | NOT_ELIGIBLE",
            "Remote, LATAM                 | NOT_ELIGIBLE",
            "Germany                       | NOT_ELIGIBLE",
            "Remote - South Africa         | ELIGIBLE",
            "Remote, ZA                    | ELIGIBLE",
            "Remote (RSA)                  | ELIGIBLE",
            "Worldwide                     | ELIGIBLE",
            "Anywhere in the world         | ELIGIBLE",
            "Remote (EMEA)                 | ELIGIBLE",
            "Remote - Africa               | ELIGIBLE",
            "Global                        | ELIGIBLE",
            "Remote - US or South Africa   | ELIGIBLE",
            "Remote                        | UNKNOWN",
            "Join us, remote-first         | UNKNOWN",
            "Cape Town                     | UNKNOWN",
            "us only                       | UNKNOWN"})
    void remoteEligibility(String location, RemoteEligibility.Result expected) {
        assertThat(RemoteEligibility.evaluate(location, List.of("ZA"), KW)).isEqualTo(expected);
    }

    @Test
    void remoteEligibilityEdges() {
        assertThat(RemoteEligibility.evaluate(null, List.of("ZA"), KW)).isEqualTo(RemoteEligibility.Result.UNKNOWN);
        assertThat(RemoteEligibility.evaluate(" ", List.of("ZA"), KW)).isEqualTo(RemoteEligibility.Result.UNKNOWN);
        assertThat(RemoteEligibility.evaluate("Remote - Germany", List.of("ZA", "DE"), KW))
                .isEqualTo(RemoteEligibility.Result.ELIGIBLE);
        assertThat(RemoteEligibility.evaluate("Worldwide", List.of("ZA"), List.of()))
                .as("keywords are configurable").isEqualTo(RemoteEligibility.Result.UNKNOWN);
    }

    @Test
    void gazetteer() {
        assertThat(Gazetteer.isCountryCode("ZA")).isTrue();
        assertThat(Gazetteer.isCountryCode("XX")).isFalse();
        assertThat(Gazetteer.isCountryCode("za")).isFalse();
        assertThat(Gazetteer.isCountryCode(null)).isFalse();
        assertThat(Gazetteer.namesCountry("Johannesburg, South Africa", "ZA")).isTrue();
        assertThat(Gazetteer.namesCountry("south africa", "ZA")).isTrue();
        assertThat(Gazetteer.namesCountry("Remote - za", "ZA")).as("codes only in upper case").isFalse();
        assertThat(Gazetteer.namesCountry("Remote", "XX")).isFalse();
        assertThat(Gazetteer.namesPlace("join us")).isFalse();
        assertThat(Gazetteer.namesPlace("eu-west office")).isFalse();
        assertThat(Gazetteer.namesPlace("EU")).isTrue();
        assertThat(Gazetteer.namesPlace("Netherlands")).isTrue();
        assertThat(Gazetteer.containsPhrase("Remote, South Africa", "south africa")).isTrue();
        assertThat(Gazetteer.containsPhrase("Remote, Southafrica", "south africa")).isFalse();
        assertThat(Gazetteer.containsPhrase("x", " ")).isFalse();
    }
}
