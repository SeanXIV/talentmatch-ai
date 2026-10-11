package com.talentmatch.preferences;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.talentmatch.service.exception.RequestValidationException;
import com.talentmatch.web.error.ErrorCode;
import com.talentmatch.web.error.FieldErrorDto;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Spec §9.1 item 12: ISO codes, limits, defaults, duplicates, full-path field errors. */
class PreferencesValidatorTest {

    private static PreferencesInput input(List<String> titles, List<String> excluded, PreferencesInput.Regions regions,
                                          List<Seniority> seniority, PreferencesInput.SalaryFloor floor,
                                          List<String> workAuth, Integer notice) {
        return new PreferencesInput(titles, excluded, regions, seniority, floor, workAuth, notice);
    }

    private static PreferencesInput empty() {
        return input(null, null, null, null, null, null, null);
    }

    private static PreferencesInput.Regions regions(List<String> countries) {
        return new PreferencesInput.Regions(countries, null, null, null);
    }

    private static Map<String, String> errors(PreferencesInput in) {
        RequestValidationException e = catchThrowableOfType(() -> PreferencesValidator.validate(in),
                RequestValidationException.class);
        assertThat(e).as("expected a validation error").isNotNull();
        assertThat(e.getCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
        Map<String, String> m = new LinkedHashMap<>();
        for (FieldErrorDto fe : e.getFieldErrors()) {
            m.put(fe.field(), fe.message());
        }
        return m;
    }

    // ------------------------------------------------------------------ defaults

    @Test
    void anEmptyInputGetsTheDefaults() {
        JobPreferences p = PreferencesValidator.validate(empty());
        assertThat(p.targetTitles()).isEmpty();
        assertThat(p.excludedTitleKeywords()).isEmpty();
        assertThat(p.regions().countries()).containsExactly("ZA");
        assertThat(p.regions().includeRemote()).isTrue();
        assertThat(p.regions().remoteScope()).isEqualTo(RemoteScope.ELIGIBLE_FROM_COUNTRIES);
        assertThat(p.regions().remoteLocationKeywords()).isEqualTo(JobPreferences.DEFAULT_REMOTE_LOCATION_KEYWORDS);
        assertThat(p.seniority()).isEmpty();
        assertThat(p.salaryFloor()).isNull();
        assertThat(p.workAuthorization()).isEmpty();
        assertThat(p.noticePeriodDays()).isNull();
    }

    @Test
    void regionsDefaultsPerField() {
        JobPreferences p = PreferencesValidator.validate(input(null, null,
                new PreferencesInput.Regions(List.of(), false, RemoteScope.ANYWHERE, List.of("remote ok")),
                null, null, null, null));
        assertThat(p.regions().countries()).as("explicit empty = any country").isEmpty();
        assertThat(p.regions().includeRemote()).isFalse();
        assertThat(p.regions().remoteScope()).isEqualTo(RemoteScope.ANYWHERE);
        assertThat(p.regions().remoteLocationKeywords()).containsExactly("remote ok");
        JobPreferences q = PreferencesValidator.validate(input(null, null, regions(null), null, null, null, null));
        assertThat(q.regions().countries()).containsExactly("ZA");
        assertThat(q.regions().includeRemote()).isTrue();
    }

    @Test
    void nullInputIsAnErrorOnTheRoot() {
        assertThat(errors(null)).containsOnlyKeys("preferences");
    }

    // ------------------------------------------------------------------ normalization

    @Test
    void textIsTrimmedCollapsedAndDeduplicated() {
        JobPreferences p = PreferencesValidator.validate(input(
                List.of("  Backend   Engineer ", "backend engineer", "Data Engineer"),
                List.of("Sales", "SALES", " Intern "), null, null, null, null, null));
        assertThat(p.targetTitles()).containsExactly("Backend Engineer", "Data Engineer");
        assertThat(p.excludedTitleKeywords()).containsExactly("Sales", "Intern");
    }

    @Test
    void countryCodesAreUpperCasedAndDeduplicated() {
        JobPreferences p = PreferencesValidator.validate(input(null, null, regions(List.of("za", " ZA ", "gb")),
                null, null, List.of("ZA", "za"), null));
        assertThat(p.regions().countries()).containsExactly("ZA", "GB");
        assertThat(p.workAuthorization()).containsExactly("ZA");
    }

    @Test
    void seniorityDuplicatesAreDropped() {
        JobPreferences p = PreferencesValidator.validate(input(null, null, null,
                List.of(Seniority.SENIOR, Seniority.MID, Seniority.SENIOR), null, null, null));
        assertThat(p.seniority()).containsExactly(Seniority.MID, Seniority.SENIOR);
    }

    @Test
    void salaryFloorIsNormalized() {
        JobPreferences p = PreferencesValidator.validate(input(null, null, null, null,
                new PreferencesInput.SalaryFloor(new BigDecimal("50000"), " zar ", SalaryPeriod.MONTH), null, 30));
        assertThat(p.salaryFloor()).isEqualTo(new JobPreferences.SalaryFloor(new BigDecimal("50000"), "ZAR",
                SalaryPeriod.MONTH));
        assertThat(p.noticePeriodDays()).isEqualTo(30);
    }

    // ------------------------------------------------------------------ field errors

    @Test
    void invalidCountryCodesHaveFullPaths() {
        Map<String, String> e = errors(input(null, null, regions(List.of("ZA", "XX", " ")), null, null,
                List.of("ZAF"), null));
        assertThat(e).containsOnlyKeys("preferences.regions.countries[1]", "preferences.regions.countries[2]",
                "preferences.workAuthorization[0]");
        assertThat(e.get("preferences.regions.countries[1]")).isEqualTo("'XX' is not an ISO country code.");
        assertThat(e.get("preferences.workAuthorization[0]")).contains("'ZAF'");
    }

    @Test
    void listLimits() {
        List<String> titles = new ArrayList<>();
        for (int i = 0; i < 21; i++) {
            titles.add("Title " + i);
        }
        List<String> keywords = new ArrayList<>();
        for (int i = 0; i < 31; i++) {
            keywords.add("Keyword " + i);
        }
        List<String> countries = Collections.nCopies(51, "ZA");
        Map<String, String> e = errors(input(titles, keywords, regions(countries), null, null, null, null));
        assertThat(e).containsOnlyKeys("preferences.targetTitles", "preferences.excludedTitleKeywords",
                "preferences.regions.countries");
        assertThat(PreferencesValidator.validate(input(titles.subList(0, 20), keywords.subList(0, 30), null, null,
                null, null, null)).targetTitles()).hasSize(20);
    }

    @Test
    void textLengthLimits() {
        Map<String, String> e = errors(input(List.of("A", "x".repeat(101), "OK title", "  "),
                List.of("y".repeat(51)), null, null, null, null, null));
        assertThat(e).containsOnlyKeys("preferences.targetTitles[0]", "preferences.targetTitles[1]",
                "preferences.targetTitles[3]", "preferences.excludedTitleKeywords[0]");
        assertThat(PreferencesValidator.validate(input(List.of("QA", "x".repeat(100)), List.of("y".repeat(50)), null,
                null, null, null, null)).targetTitles()).hasSize(2);
    }

    @Test
    void seniorityUnknownOrNullIsAFieldError() {
        Map<String, String> e = errors(input(null, null, null,
                Arrays.asList(Seniority.SENIOR, Seniority.UNKNOWN, null), null, null, null));
        assertThat(e).containsOnlyKeys("preferences.seniority[1]", "preferences.seniority[2]");
    }

    @Test
    void salaryFloorErrors() {
        assertThat(errors(input(null, null, null, null,
                new PreferencesInput.SalaryFloor(new BigDecimal("100"), "ZAR", SalaryPeriod.DAY), null, null)))
                .containsOnlyKeys("preferences.salaryFloor.period");
        assertThat(errors(input(null, null, null, null,
                new PreferencesInput.SalaryFloor(new BigDecimal("100"), "ZAR", SalaryPeriod.HOUR), null, null)))
                .containsOnlyKeys("preferences.salaryFloor.period");
        assertThat(errors(input(null, null, null, null,
                new PreferencesInput.SalaryFloor(BigDecimal.ZERO, "XYZ", null), null, null)))
                .containsOnlyKeys("preferences.salaryFloor.amount", "preferences.salaryFloor.currency",
                        "preferences.salaryFloor.period");
        assertThat(errors(input(null, null, null, null,
                new PreferencesInput.SalaryFloor(null, " ", SalaryPeriod.YEAR), null, null)))
                .containsOnlyKeys("preferences.salaryFloor.amount", "preferences.salaryFloor.currency");
        assertThat(errors(input(null, null, null, null,
                new PreferencesInput.SalaryFloor(new BigDecimal("1000000000001"), "ZAR", SalaryPeriod.YEAR), null, null)))
                .containsOnlyKeys("preferences.salaryFloor.amount");
        assertThat(errors(input(null, null, null, null,
                new PreferencesInput.SalaryFloor(new BigDecimal("-1"), "ZAR", SalaryPeriod.YEAR), null, null)))
                .containsOnlyKeys("preferences.salaryFloor.amount");
    }

    @Test
    void noticePeriodRange() {
        assertThat(errors(input(null, null, null, null, null, null, 366))).containsOnlyKeys("preferences.noticePeriodDays");
        assertThat(errors(input(null, null, null, null, null, null, -1))).containsOnlyKeys("preferences.noticePeriodDays");
        assertThat(PreferencesValidator.validate(input(null, null, null, null, null, null, 0)).noticePeriodDays()).isZero();
        assertThat(PreferencesValidator.validate(input(null, null, null, null, null, null, 365)).noticePeriodDays())
                .isEqualTo(365);
    }

    @Test
    void allErrorsAreReportedTogether() {
        Map<String, String> e = errors(input(List.of("A"), null, regions(List.of("XX")), List.of(Seniority.UNKNOWN),
                new PreferencesInput.SalaryFloor(BigDecimal.ONE, "ZAR", SalaryPeriod.DAY), List.of("QQ"), 999));
        assertThat(e).containsOnlyKeys("preferences.targetTitles[0]", "preferences.regions.countries[0]",
                "preferences.seniority[0]", "preferences.salaryFloor.period", "preferences.workAuthorization[0]",
                "preferences.noticePeriodDays");
    }
}
