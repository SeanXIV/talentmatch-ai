package com.talentmatch.preferences;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * The owner's job preferences, entered by hand (job_preferences, V5). Never derived from the CV
 * and never written by any AI code path: {@link PreferencesValidator} is the only way in.
 * The compact constructors only make the record null-safe; defaults for omitted fields are
 * applied by the validator.
 *
 * @param targetTitles          0..20 titles; empty = any title
 * @param excludedTitleKeywords 0..30 keywords, e.g. "Intern", "Sales"
 * @param regions               where the owner wants to work
 * @param seniority             empty = any; never UNKNOWN
 * @param salaryFloor           nullable
 * @param workAuthorization     ISO 3166 alpha-2 codes where the owner may work; empty = not checked
 * @param noticePeriodDays      0..365, nullable; only flags postings (used again in Phase 7)
 */
public record JobPreferences(
        List<String> targetTitles,
        List<String> excludedTitleKeywords,
        Regions regions,
        Set<Seniority> seniority,
        SalaryFloor salaryFloor,
        List<String> workAuthorization,
        Integer noticePeriodDays) {

    public static final List<String> DEFAULT_COUNTRIES = List.of("ZA");
    public static final List<String> DEFAULT_REMOTE_LOCATION_KEYWORDS =
            List.of("worldwide", "anywhere", "global", "emea", "africa", "south africa");

    public JobPreferences {
        targetTitles = copy(targetTitles);
        excludedTitleKeywords = copy(excludedTitleKeywords);
        regions = regions == null ? Regions.defaults() : regions;
        seniority = enumSet(seniority);
        workAuthorization = copy(workAuthorization);
    }

    /**
     * No filters at all (the meaning of "no preferences saved"): any country, remote jobs from
     * anywhere. Not the same as {@link Regions#defaults()}, which fills in omitted fields of saved
     * preferences.
     */
    public static JobPreferences none() {
        return new JobPreferences(List.of(), List.of(), Regions.any(), Set.of(), null, List.of(), null);
    }

    /**
     * @param countries              ISO 3166 alpha-2 codes; empty = any country
     * @param includeRemote          whether remote jobs are wanted at all
     * @param remoteScope            which remote jobs
     * @param remoteLocationKeywords location words that make a remote job eligible ("worldwide", …)
     */
    public record Regions(List<String> countries, boolean includeRemote, RemoteScope remoteScope,
                          List<String> remoteLocationKeywords) {

        public Regions {
            countries = copy(countries);
            remoteScope = remoteScope == null ? RemoteScope.ELIGIBLE_FROM_COUNTRIES : remoteScope;
            remoteLocationKeywords = copy(remoteLocationKeywords);
        }

        /** Defaults for an omitted {@code regions} in saved preferences: ZA, remote jobs open to ZA. */
        public static Regions defaults() {
            return new Regions(DEFAULT_COUNTRIES, true, RemoteScope.ELIGIBLE_FROM_COUNTRIES,
                    DEFAULT_REMOTE_LOCATION_KEYWORDS);
        }

        /** No region filter at all: any country, remote jobs from anywhere. */
        public static Regions any() {
            return new Regions(List.of(), true, RemoteScope.ANYWHERE, List.of());
        }
    }

    /**
     * @param amount   positive amount
     * @param currency ISO 4217 code, upper case
     * @param period   YEAR or MONTH
     */
    public record SalaryFloor(BigDecimal amount, String currency, SalaryPeriod period) {

        public SalaryFloor {
            Objects.requireNonNull(amount, "amount");
            Objects.requireNonNull(currency, "currency");
            Objects.requireNonNull(period, "period");
        }
    }

    private static List<String> copy(List<String> list) {
        return list == null ? List.of() : List.copyOf(list);
    }

    /** Unmodifiable, in enum order (so the stored JSON is stable). */
    private static Set<Seniority> enumSet(Collection<Seniority> values) {
        EnumSet<Seniority> set = EnumSet.noneOf(Seniority.class);
        if (values != null) {
            values.stream().filter(Objects::nonNull).forEach(set::add);
        }
        return Collections.unmodifiableSet(set);
    }
}
