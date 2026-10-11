package com.talentmatch.preferences;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Checks a posting against the owner's preferences (pure). It drops a posting only on evidence
 * against it (decision j): missing data passes, with a flag. Every rule runs, so a FILTERED
 * verdict lists all of its reasons, in a fixed order.
 */
public final class PreferenceFilter {

    /** Why a posting was filtered. Stored in feed_job.filter_reasons. */
    public enum Reason {
        TITLE,
        EXCLUDED_KEYWORD,
        REGION,
        REMOTE_NOT_WANTED,
        REMOTE_REGION,
        SENIORITY,
        SALARY,
        WORK_PERMIT
    }

    /** Something the owner should check; never filters. Stored in feed_job.filter_flags. */
    public enum Flag {
        LOCATION_UNKNOWN,
        REMOTE_ELIGIBILITY_UNKNOWN,
        SALARY_OTHER_CURRENCY,
        IMMEDIATE_START
    }

    /** pass is true exactly when reasons is empty. */
    public record Verdict(boolean pass, List<Reason> reasons, List<Flag> flags) {

        public Verdict {
            reasons = List.copyOf(reasons);
            flags = List.copyOf(flags);
        }
    }

    private static final Pattern IMMEDIATE_START =
            Pattern.compile("(?i)immediate (start|availability)|start immediately");

    private PreferenceFilter() {
    }

    public static Verdict evaluate(FeedFacts facts, JobPreferences prefs) {
        JobPreferences p = prefs == null ? JobPreferences.none() : prefs;
        JobPreferences.Regions regions = p.regions();
        List<Reason> reasons = new ArrayList<>();
        List<Flag> flags = new ArrayList<>();
        boolean remote = facts.remote();

        // TITLE / EXCLUDED_KEYWORD (the title is always known)
        if (!TitleMatcher.anyMatches(p.targetTitles(), facts.title())) {
            reasons.add(Reason.TITLE);
        }
        for (String keyword : p.excludedTitleKeywords()) {
            if (TitleMatcher.containsKeyword(facts.title(), keyword)) {
                reasons.add(Reason.EXCLUDED_KEYWORD);
                break;
            }
        }

        // REGION (on-site and hybrid jobs; empty countries = any country)
        boolean countriesKnown = !facts.countryCodes().isEmpty();
        if (!remote) {
            if (!countriesKnown) {
                if (!regions.countries().isEmpty() || !p.workAuthorization().isEmpty()) {
                    flags.add(Flag.LOCATION_UNKNOWN);
                }
            } else if (!regions.countries().isEmpty() && !intersects(facts.countryCodes(), regions.countries())) {
                reasons.add(Reason.REGION);
            }
        }

        // REMOTE_NOT_WANTED / REMOTE_REGION (empty countries = any country, so no eligibility check)
        if (remote) {
            if (!regions.includeRemote()) {
                reasons.add(Reason.REMOTE_NOT_WANTED);
            } else if (regions.remoteScope() == RemoteScope.ELIGIBLE_FROM_COUNTRIES
                    && !regions.countries().isEmpty()) {
                switch (RemoteEligibility.evaluate(facts.locationText(), regions.countries(),
                        regions.remoteLocationKeywords())) {
                    case NOT_ELIGIBLE -> reasons.add(Reason.REMOTE_REGION);
                    case UNKNOWN -> flags.add(Flag.REMOTE_ELIGIBILITY_UNKNOWN);
                    case ELIGIBLE -> { }
                }
            }
        }

        // SENIORITY (UNKNOWN passes)
        if (!p.seniority().isEmpty() && facts.seniority() != Seniority.UNKNOWN
                && !p.seniority().contains(facts.seniority())) {
            reasons.add(Reason.SENIORITY);
        }

        // SALARY (only a stated, non-estimated maximum in the floor's currency can filter)
        JobPreferences.SalaryFloor floor = p.salaryFloor();
        if (floor != null && facts.salaryMax() != null && !facts.salaryEstimated() && facts.salaryCurrency() != null) {
            if (!facts.salaryCurrency().toUpperCase(Locale.ROOT).equals(floor.currency())) {
                flags.add(Flag.SALARY_OTHER_CURRENCY);
            } else {
                Optional<BigDecimal> max = SalaryNormalizer.annualize(facts.salaryMax(), facts.salaryPeriod());
                Optional<BigDecimal> min = SalaryNormalizer.annualize(floor.amount(), floor.period());
                if (max.isPresent() && min.isPresent() && max.get().compareTo(min.get()) < 0) {
                    reasons.add(Reason.SALARY);
                }
            }
        }

        // WORK_PERMIT (on-site and hybrid jobs with known countries)
        if (!remote && countriesKnown && !p.workAuthorization().isEmpty()
                && !intersects(facts.countryCodes(), p.workAuthorization())) {
            reasons.add(Reason.WORK_PERMIT);
        }

        // Notice period: flag only
        if (p.noticePeriodDays() != null && p.noticePeriodDays() > 0
                && (matches(IMMEDIATE_START, facts.title()) || matches(IMMEDIATE_START, facts.text()))) {
            flags.add(Flag.IMMEDIATE_START);
        }

        return new Verdict(reasons.isEmpty(), reasons, flags);
    }

    private static boolean intersects(List<String> a, List<String> b) {
        for (String x : a) {
            if (x != null && b.contains(x.toUpperCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private static boolean matches(Pattern pattern, String text) {
        return text != null && pattern.matcher(text).find();
    }
}
