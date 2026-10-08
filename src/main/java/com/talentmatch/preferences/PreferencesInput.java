package com.talentmatch.preferences;

import java.math.BigDecimal;
import java.util.List;

/**
 * Preferences as sent by the owner (every field optional). {@link PreferencesValidator} turns it
 * into {@link JobPreferences}, applying defaults for omitted fields. Unknown fields and unknown
 * enum values are rejected by the web layer (400 MALFORMED_REQUEST).
 */
public record PreferencesInput(
        List<String> targetTitles,
        List<String> excludedTitleKeywords,
        Regions regions,
        List<Seniority> seniority,
        SalaryFloor salaryFloor,
        List<String> workAuthorization,
        Integer noticePeriodDays) {

    public record Regions(List<String> countries, Boolean includeRemote, RemoteScope remoteScope,
                          List<String> remoteLocationKeywords) {
    }

    public record SalaryFloor(BigDecimal amount, String currency, SalaryPeriod period) {
    }
}
