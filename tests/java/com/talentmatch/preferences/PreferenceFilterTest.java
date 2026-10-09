package com.talentmatch.preferences;

import static com.talentmatch.preferences.PreferenceFilter.Flag.IMMEDIATE_START;
import static com.talentmatch.preferences.PreferenceFilter.Flag.LOCATION_UNKNOWN;
import static com.talentmatch.preferences.PreferenceFilter.Flag.REMOTE_ELIGIBILITY_UNKNOWN;
import static com.talentmatch.preferences.PreferenceFilter.Flag.SALARY_OTHER_CURRENCY;
import static com.talentmatch.preferences.PreferenceFilter.Reason.EXCLUDED_KEYWORD;
import static com.talentmatch.preferences.PreferenceFilter.Reason.REGION;
import static com.talentmatch.preferences.PreferenceFilter.Reason.REMOTE_NOT_WANTED;
import static com.talentmatch.preferences.PreferenceFilter.Reason.REMOTE_REGION;
import static com.talentmatch.preferences.PreferenceFilter.Reason.SALARY;
import static com.talentmatch.preferences.PreferenceFilter.Reason.SENIORITY;
import static com.talentmatch.preferences.PreferenceFilter.Reason.TITLE;
import static com.talentmatch.preferences.PreferenceFilter.Reason.WORK_PERMIT;
import static org.assertj.core.api.Assertions.assertThat;

import com.talentmatch.preferences.JobPreferences.Regions;
import com.talentmatch.preferences.JobPreferences.SalaryFloor;
import com.talentmatch.preferences.PreferenceFilter.Verdict;
import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Spec §9.1 item 11: every row of the §4.8 table, both the evidence and the missing-data cases. */
class PreferenceFilterTest {

    // ------------------------------------------------------------------ builders

    private static final class F {
        String title = "Backend Engineer";
        String text;
        List<String> countries = List.of("ZA");
        Workplace workplace = Workplace.ONSITE;
        String location;
        Seniority seniority;
        BigDecimal salaryMax;
        String currency;
        SalaryPeriod period;
        boolean estimated;

        F title(String t) { title = t; return this; }
        F text(String t) { text = t; return this; }
        F countries(String... c) { countries = List.of(c); return this; }
        F remote(String loc) { workplace = Workplace.REMOTE; location = loc; countries = List.of(); return this; }
        F hybrid() { workplace = Workplace.HYBRID; return this; }
        F seniority(Seniority s) { seniority = s; return this; }
        F salary(String max, String cur, SalaryPeriod p) {
            salaryMax = new BigDecimal(max); currency = cur; period = p; return this;
        }
        F estimated() { estimated = true; return this; }

        FeedFacts build() {
            return new FeedFacts(title, text, countries, workplace, location,
                    seniority == null ? Seniority.fromTitle(title) : seniority, salaryMax, currency, period, estimated);
        }
    }

    private static F facts() {
        return new F();
    }

    private static final class P {
        List<String> titles = List.of();
        List<String> excluded = List.of();
        List<String> countries = List.of("ZA");
        boolean includeRemote = true;
        RemoteScope scope = RemoteScope.ELIGIBLE_FROM_COUNTRIES;
        List<String> remoteKeywords = JobPreferences.DEFAULT_REMOTE_LOCATION_KEYWORDS;
        Set<Seniority> seniority = Set.of();
        SalaryFloor floor;
        List<String> workAuth = List.of();
        Integer notice;

        P titles(String... t) { titles = List.of(t); return this; }
        P excluded(String... k) { excluded = List.of(k); return this; }
        P countries(String... c) { countries = List.of(c); return this; }
        P noRemote() { includeRemote = false; return this; }
        P anywhere() { scope = RemoteScope.ANYWHERE; return this; }
        P seniority(Seniority... s) { seniority = Set.of(s); return this; }
        P floor(String amount, String cur, SalaryPeriod p) { floor = new SalaryFloor(new BigDecimal(amount), cur, p); return this; }
        P workAuth(String... c) { workAuth = List.of(c); return this; }
        P notice(int days) { notice = days; return this; }

        JobPreferences build() {
            return new JobPreferences(titles, excluded, new Regions(countries, includeRemote, scope, remoteKeywords),
                    seniority, floor, workAuth, notice);
        }
    }

    private static P prefs() {
        return new P();
    }

    private static Verdict eval(F f, P p) {
        Verdict v = PreferenceFilter.evaluate(f.build(), p.build());
        assertThat(v.pass()).as("pass iff no reasons: %s", v).isEqualTo(v.reasons().isEmpty());
        return v;
    }

    // ------------------------------------------------------------------ no preferences

    @Test
    void defaultPreferencesPassAnOrdinaryJob() {
        Verdict v = eval(facts(), prefs());
        assertThat(v.pass()).isTrue();
        assertThat(v.reasons()).isEmpty();
        assertThat(v.flags()).isEmpty();
    }

    /** §3.4: "no row means defaults (no preferences = no filters)"; JobPreferences.none() says "No filters at all". */
    @Test
    void noPreferencesMeansNoFilters() {
        FeedFacts onsiteGermany = facts().countries("DE").build();
        FeedFacts remoteUsOnly = facts().remote("Remote, US only").build();
        assertThat(PreferenceFilter.evaluate(onsiteGermany, null).reasons()).isEmpty();
        assertThat(PreferenceFilter.evaluate(remoteUsOnly, null).reasons()).isEmpty();
        assertThat(PreferenceFilter.evaluate(onsiteGermany, JobPreferences.none()).reasons()).isEmpty();
        assertThat(PreferenceFilter.evaluate(remoteUsOnly, JobPreferences.none()).reasons()).isEmpty();
    }

    // ------------------------------------------------------------------ TITLE / EXCLUDED_KEYWORD

    @Test
    void title() {
        P p = prefs().titles("Backend Developer", "Data Engineer");
        assertThat(eval(facts().title("Senior Back-End Engineer"), p).reasons()).isEmpty();
        assertThat(eval(facts().title("Back end programmer"), p).reasons()).isEmpty();
        assertThat(eval(facts().title("Data Engineer II"), p).reasons()).isEmpty();
        assertThat(eval(facts().title("Frontend Engineer"), p).reasons()).containsExactly(TITLE);
        assertThat(eval(facts().title("Backend"), p).reasons()).as("every target token must appear").containsExactly(TITLE);
        assertThat(eval(facts().title("Anything at all"), prefs()).reasons()).as("no targets = any title").isEmpty();
    }

    @Test
    void excludedKeyword() {
        P p = prefs().excluded("Sales", "Intern");
        assertThat(eval(facts().title("Sales Engineer"), p).reasons()).containsExactly(EXCLUDED_KEYWORD);
        assertThat(eval(facts().title("Software Intern"), p).reasons()).containsExactly(EXCLUDED_KEYWORD);
        assertThat(eval(facts().title("International Backend Engineer"), p).reasons()).as("token match").isEmpty();
        assertThat(eval(facts().title("Salesforce Developer"), p).reasons()).isEmpty();
    }

    // ------------------------------------------------------------------ REGION / WORK_PERMIT

    @Test
    void region() {
        assertThat(eval(facts().countries("DE"), prefs()).reasons()).containsExactly(REGION);
        assertThat(eval(facts().countries("DE").hybrid(), prefs()).reasons()).containsExactly(REGION);
        assertThat(eval(facts().countries("DE", "ZA"), prefs()).reasons()).isEmpty();
        assertThat(eval(facts().countries("za"), prefs()).reasons()).as("case of the posting's codes").isEmpty();
        assertThat(eval(facts().countries("DE"), prefs().countries()).reasons()).as("empty = any country").isEmpty();
    }

    @Test
    void regionUnknownPassesWithAFlag() {
        Verdict v = eval(facts().countries(), prefs());
        assertThat(v.pass()).isTrue();
        assertThat(v.flags()).containsExactly(LOCATION_UNKNOWN);
    }

    @Test
    void workPermit() {
        P p = prefs().countries().workAuth("ZA");
        assertThat(eval(facts().countries("GB"), p).reasons()).containsExactly(WORK_PERMIT);
        assertThat(eval(facts().countries("GB", "ZA"), p).reasons()).isEmpty();
        assertThat(eval(facts().countries("GB").hybrid(), p).reasons()).containsExactly(WORK_PERMIT);
        assertThat(eval(facts().remote("Worldwide"), p).reasons()).as("not for remote jobs").isEmpty();
        assertThat(eval(facts().countries("GB"), prefs().countries()).reasons()).as("empty = not checked").isEmpty();
        assertThat(eval(facts().countries(), p).flags()).containsExactly(LOCATION_UNKNOWN);
    }

    // ------------------------------------------------------------------ remote

    @Test
    void remoteNotWanted() {
        assertThat(eval(facts().remote("Worldwide"), prefs().noRemote()).reasons()).containsExactly(REMOTE_NOT_WANTED);
        assertThat(eval(facts(), prefs().noRemote()).reasons()).isEmpty();
    }

    @Test
    void remoteRegion() {
        assertThat(eval(facts().remote("Remote, US only"), prefs()).reasons()).containsExactly(REMOTE_REGION);
        assertThat(eval(facts().remote("Remote - Europe"), prefs()).reasons()).containsExactly(REMOTE_REGION);
        assertThat(eval(facts().remote("Remote (United Kingdom)"), prefs()).reasons()).containsExactly(REMOTE_REGION);
        assertThat(eval(facts().remote("Remote - South Africa"), prefs()).reasons()).isEmpty();
        assertThat(eval(facts().remote("Remote, RSA"), prefs()).reasons()).isEmpty();
        assertThat(eval(facts().remote("Worldwide"), prefs()).reasons()).isEmpty();
        assertThat(eval(facts().remote("Remote (EMEA)"), prefs()).reasons()).isEmpty();
        assertThat(eval(facts().remote("Remote - US or South Africa"), prefs()).reasons()).isEmpty();
        assertThat(eval(facts().remote("Remote, US only"), prefs().anywhere()).reasons()).as("scope ANYWHERE").isEmpty();
        // a remote job is never REGION-filtered by its own country codes
        F remoteDe = facts().remote("Worldwide");
        remoteDe.countries = List.of("DE");
        assertThat(eval(remoteDe, prefs()).reasons()).isEmpty();
    }

    @Test
    void remoteRegionUnknownPassesWithAFlag() {
        for (String loc : new String[] {"Remote", "Cape Town or remote", "Join us from home", null}) {
            Verdict v = eval(facts().remote(loc), prefs());
            assertThat(v.pass()).as(loc).isTrue();
            assertThat(v.flags()).as(loc).containsExactly(REMOTE_ELIGIBILITY_UNKNOWN);
        }
    }

    /** Regions.countries: "empty = any country" (JobPreferences javadoc), so no remote job can be ineligible. */
    @Test
    void remoteRegionWithAnyCountry() {
        assertThat(eval(facts().remote("Remote, US only"), prefs().countries()).reasons()).isEmpty();
    }

    // ------------------------------------------------------------------ SENIORITY

    @Test
    void seniority() {
        P p = prefs().seniority(Seniority.MID, Seniority.SENIOR);
        assertThat(eval(facts().title("Senior Backend Engineer"), p).reasons()).isEmpty();
        assertThat(eval(facts().title("Junior Backend Engineer"), p).reasons()).containsExactly(SENIORITY);
        assertThat(eval(facts().title("Backend Engineer"), p).reasons()).as("UNKNOWN passes").isEmpty();
        assertThat(eval(facts().seniority(Seniority.LEAD), p).reasons()).containsExactly(SENIORITY);
        assertThat(eval(facts().title("Junior Backend Engineer"), prefs()).reasons()).as("empty = any").isEmpty();
    }

    // ------------------------------------------------------------------ SALARY

    @Test
    void salaryBelowTheFloorIsFiltered() {
        P yearly = prefs().floor("600000", "ZAR", SalaryPeriod.YEAR);
        assertThat(eval(facts().salary("40000", "ZAR", SalaryPeriod.MONTH), yearly).reasons())
                .as("40k × 12 = 480k < 600k").containsExactly(SALARY);
        assertThat(eval(facts().salary("50000", "ZAR", SalaryPeriod.MONTH), yearly).reasons())
                .as("50k × 12 = 600k, equal passes").isEmpty();
        assertThat(eval(facts().salary("700000", "zar", SalaryPeriod.YEAR), yearly).reasons()).isEmpty();
        P monthly = prefs().floor("50000", "ZAR", SalaryPeriod.MONTH);
        assertThat(eval(facts().salary("590000", "ZAR", SalaryPeriod.YEAR), monthly).reasons()).containsExactly(SALARY);
    }

    @Test
    void salaryMissingDataPasses() {
        P p = prefs().floor("600000", "ZAR", SalaryPeriod.YEAR);
        assertThat(eval(facts(), p).reasons()).as("no salary").isEmpty();
        assertThat(eval(facts().salary("100", "ZAR", SalaryPeriod.MONTH).estimated(), p).reasons())
                .as("estimated never filters").isEmpty();
        assertThat(eval(facts().salary("100", "ZAR", SalaryPeriod.HOUR), p).reasons()).as("hourly").isEmpty();
        assertThat(eval(facts().salary("100", "ZAR", SalaryPeriod.DAY), p).reasons()).as("daily").isEmpty();
        assertThat(eval(facts().salary("100", "ZAR", null), p).reasons()).as("no period").isEmpty();
        assertThat(eval(facts().salary("100", "ZAR", SalaryPeriod.MONTH), prefs()).reasons()).as("no floor").isEmpty();
    }

    @Test
    void salaryInAnotherCurrencyIsFlagged() {
        Verdict v = eval(facts().salary("1000", "USD", SalaryPeriod.MONTH), prefs().floor("600000", "ZAR", SalaryPeriod.YEAR));
        assertThat(v.pass()).isTrue();
        assertThat(v.flags()).containsExactly(SALARY_OTHER_CURRENCY);
    }

    // ------------------------------------------------------------------ notice period

    @Test
    void immediateStartIsOnlyAFlag() {
        P p = prefs().notice(30);
        for (String text : new String[] {"Immediate start.", "We need immediate availability", "Start immediately!"}) {
            Verdict v = eval(facts().text(text), p);
            assertThat(v.pass()).as(text).isTrue();
            assertThat(v.flags()).as(text).containsExactly(IMMEDIATE_START);
        }
        assertThat(eval(facts().title("Backend Engineer - Immediate Start"), p).flags()).containsExactly(IMMEDIATE_START);
        assertThat(eval(facts().text("Immediate start."), prefs().notice(0)).flags()).isEmpty();
        assertThat(eval(facts().text("Immediate start."), prefs()).flags()).isEmpty();
        assertThat(eval(facts().text("Start date flexible."), p).flags()).isEmpty();
    }

    // ------------------------------------------------------------------ all rules together

    @Test
    void everyRuleRunsAndReasonsComeInTableOrder() {
        P p = prefs().titles("Data Engineer").excluded("Sales").seniority(Seniority.SENIOR)
                .floor("600000", "ZAR", SalaryPeriod.YEAR).workAuth("ZA");
        Verdict v = eval(facts().title("Junior Sales Engineer").countries("DE")
                .salary("10000", "ZAR", SalaryPeriod.MONTH), p);
        assertThat(v.pass()).isFalse();
        assertThat(v.reasons()).containsExactly(TITLE, EXCLUDED_KEYWORD, REGION, SENIORITY, SALARY, WORK_PERMIT);

        Verdict remote = eval(facts().title("Junior Sales Engineer").remote("US only"), prefs().noRemote()
                .titles("Data Engineer"));
        assertThat(remote.reasons()).containsExactly(TITLE, REMOTE_NOT_WANTED);
    }

    @Test
    void verdictListsAreImmutable() {
        Verdict v = eval(facts(), prefs());
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> v.reasons().add(TITLE))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
