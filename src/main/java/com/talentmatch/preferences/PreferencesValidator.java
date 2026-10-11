package com.talentmatch.preferences;

import com.talentmatch.service.FieldErrors;
import com.talentmatch.service.TextNormalizer;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Currency;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Validates and normalizes the owner's preferences (pure). The only way preferences get in.
 * Field errors use full paths ({@code preferences.regions.countries[1]}). Omitted fields get
 * defaults; text is trimmed with whitespace runs collapsed; duplicates (ignoring case) are dropped.
 */
public final class PreferencesValidator {

    public static final String ROOT = "preferences";

    public static final int MAX_TARGET_TITLES = 20;
    public static final int MIN_TITLE_LENGTH = 2;
    public static final int MAX_TITLE_LENGTH = 100;
    public static final int MAX_EXCLUDED_KEYWORDS = 30;
    public static final int MAX_KEYWORD_LENGTH = 50;
    public static final int MAX_COUNTRIES = 50;
    public static final int MAX_REMOTE_KEYWORDS = 30;
    public static final int MAX_NOTICE_PERIOD_DAYS = 365;
    public static final BigDecimal MAX_SALARY = new BigDecimal("1000000000000");

    private static final Set<String> CURRENCIES = Currency.getAvailableCurrencies().stream()
            .map(Currency::getCurrencyCode).collect(Collectors.toUnmodifiableSet());

    private PreferencesValidator() {
    }

    /** @throws com.talentmatch.service.exception.RequestValidationException with every field error */
    public static JobPreferences validate(PreferencesInput input) {
        FieldErrors errors = new FieldErrors();
        if (input == null) {
            errors.add(ROOT, "Send the preferences to save, e.g. {\"preferences\": {\"targetTitles\": "
                    + "[\"Backend Engineer\"]}}.");
            errors.throwIfAny();
        }
        List<String> titles = texts(input.targetTitles(), ROOT + ".targetTitles", MAX_TARGET_TITLES,
                MIN_TITLE_LENGTH, MAX_TITLE_LENGTH, "target titles", errors);
        List<String> excluded = texts(input.excludedTitleKeywords(), ROOT + ".excludedTitleKeywords",
                MAX_EXCLUDED_KEYWORDS, MIN_TITLE_LENGTH, MAX_KEYWORD_LENGTH, "excluded keywords", errors);
        JobPreferences.Regions regions = regions(input.regions(), errors);
        Set<Seniority> seniority = seniority(input.seniority(), errors);
        JobPreferences.SalaryFloor floor = salaryFloor(input.salaryFloor(), errors);
        List<String> workAuthorization = countries(input.workAuthorization(), ROOT + ".workAuthorization", errors);
        Integer notice = input.noticePeriodDays();
        if (notice != null && (notice < 0 || notice > MAX_NOTICE_PERIOD_DAYS)) {
            errors.add(ROOT + ".noticePeriodDays", "Notice period must be 0 to " + MAX_NOTICE_PERIOD_DAYS
                    + " days (got " + notice + ").");
        }
        errors.throwIfAny();
        return new JobPreferences(titles, excluded, regions, seniority, floor, workAuthorization, notice);
    }

    private static JobPreferences.Regions regions(PreferencesInput.Regions in, FieldErrors errors) {
        if (in == null) {
            return JobPreferences.Regions.defaults();
        }
        String path = ROOT + ".regions";
        List<String> countries = in.countries() == null
                ? JobPreferences.DEFAULT_COUNTRIES
                : countries(in.countries(), path + ".countries", errors);
        List<String> keywords = in.remoteLocationKeywords() == null
                ? JobPreferences.DEFAULT_REMOTE_LOCATION_KEYWORDS
                : texts(in.remoteLocationKeywords(), path + ".remoteLocationKeywords", MAX_REMOTE_KEYWORDS,
                        MIN_TITLE_LENGTH, MAX_KEYWORD_LENGTH, "remote location keywords", errors);
        boolean includeRemote = in.includeRemote() == null || in.includeRemote();
        return new JobPreferences.Regions(countries, includeRemote,
                in.remoteScope() == null ? RemoteScope.ELIGIBLE_FROM_COUNTRIES : in.remoteScope(), keywords);
    }

    private static Set<Seniority> seniority(List<Seniority> in, FieldErrors errors) {
        Set<Seniority> out = EnumSet.noneOf(Seniority.class);
        if (in == null) {
            return out;
        }
        for (int i = 0; i < in.size(); i++) {
            Seniority s = in.get(i);
            String field = ROOT + ".seniority[" + i + "]";
            if (s == null) {
                errors.add(field, "Seniority entry must not be null.");
            } else if (s == Seniority.UNKNOWN) {
                errors.add(field, "UNKNOWN can't be chosen; leave seniority empty to accept any level.");
            } else {
                out.add(s);
            }
        }
        return out;
    }

    private static JobPreferences.SalaryFloor salaryFloor(PreferencesInput.SalaryFloor in, FieldErrors errors) {
        if (in == null) {
            return null;
        }
        String path = ROOT + ".salaryFloor";
        boolean ok = true;
        BigDecimal amount = in.amount();
        if (amount == null) {
            errors.add(path + ".amount", "Salary floor amount is required (or leave salaryFloor out).");
            ok = false;
        } else if (amount.signum() <= 0) {
            errors.add(path + ".amount", "Salary floor amount must be greater than 0.");
            ok = false;
        } else if (amount.compareTo(MAX_SALARY) > 0) {
            errors.add(path + ".amount", "Salary floor amount is too large.");
            ok = false;
        }
        String currency = TextNormalizer.text(in.currency());
        if (currency == null) {
            errors.add(path + ".currency", "Currency is required, e.g. ZAR.");
            ok = false;
        } else {
            currency = currency.toUpperCase(Locale.ROOT);
            if (!CURRENCIES.contains(currency)) {
                errors.add(path + ".currency", "'" + in.currency().strip() + "' is not an ISO 4217 currency code "
                        + "(e.g. ZAR, USD, EUR).");
                ok = false;
            }
        }
        SalaryPeriod period = in.period();
        if (period == null) {
            errors.add(path + ".period", "Period is required: YEAR or MONTH.");
            ok = false;
        } else if (period != SalaryPeriod.YEAR && period != SalaryPeriod.MONTH) {
            errors.add(path + ".period", "Use YEAR or MONTH for a salary floor (got " + period + ").");
            ok = false;
        }
        return ok ? new JobPreferences.SalaryFloor(amount, currency, period) : null;
    }

    /** ISO 3166 alpha-2 codes, upper-cased, duplicates dropped. Empty is allowed. */
    private static List<String> countries(List<String> in, String path, FieldErrors errors) {
        List<String> out = new ArrayList<>();
        if (in == null) {
            return out;
        }
        if (in.size() > MAX_COUNTRIES) {
            errors.add(path, "At most " + MAX_COUNTRIES + " countries (got " + in.size() + ").");
            return out;
        }
        for (int i = 0; i < in.size(); i++) {
            String raw = in.get(i);
            String field = path + "[" + i + "]";
            String code = TextNormalizer.text(raw);
            if (code == null) {
                errors.add(field, "Country code must not be blank.");
                continue;
            }
            code = code.toUpperCase(Locale.ROOT);
            if (!Gazetteer.isCountryCode(code)) {
                errors.add(field, "'" + raw.strip() + "' is not an ISO country code.");
                continue;
            }
            if (!out.contains(code)) {
                out.add(code);
            }
        }
        return out;
    }

    /** Trimmed text items with collapsed whitespace, duplicates (ignoring case) dropped. */
    private static List<String> texts(List<String> in, String path, int maxItems, int minLength, int maxLength,
                                      String label, FieldErrors errors) {
        List<String> out = new ArrayList<>();
        if (in == null) {
            return out;
        }
        if (in.size() > maxItems) {
            errors.add(path, "At most " + maxItems + " " + label + " (got " + in.size() + ").");
            return out;
        }
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < in.size(); i++) {
            String field = path + "[" + i + "]";
            String value = TextNormalizer.skillName(in.get(i));
            if (value == null) {
                errors.add(field, "Must not be blank.");
                continue;
            }
            if (value.length() < minLength || value.length() > maxLength) {
                errors.add(field, "Must be " + minLength + " to " + maxLength + " characters (got "
                        + value.length() + ").");
                continue;
            }
            if (seen.add(value.toLowerCase(Locale.ROOT))) {
                out.add(value);
            }
        }
        return out;
    }
}
