package com.talentmatch.preferences;

import java.math.BigDecimal;
import java.util.List;

/**
 * The posting facts the preference filter sees (built by the feed from a feed job and its
 * canonical posting). Every field except {@code title} may be missing.
 *
 * @param title           job title
 * @param text            plain-text description (for the immediate-start flag), or null
 * @param countryCodes    ISO alpha-2 codes of the job's locations; empty = unknown
 * @param workplace       REMOTE / HYBRID / ONSITE / UNKNOWN (null = UNKNOWN)
 * @param locationText    free-text location, or null
 * @param seniority       from the title (null = UNKNOWN)
 * @param salaryMax       upper bound of the advertised salary, or null
 * @param salaryCurrency  ISO 4217, or null
 * @param salaryPeriod    period of the amount, or null
 * @param salaryEstimated true when the provider estimated the salary (never used for filtering)
 */
public record FeedFacts(String title, String text, List<String> countryCodes, Workplace workplace,
                        String locationText, Seniority seniority, BigDecimal salaryMax, String salaryCurrency,
                        SalaryPeriod salaryPeriod, boolean salaryEstimated) {

    public FeedFacts {
        countryCodes = countryCodes == null ? List.of() : List.copyOf(countryCodes);
        workplace = workplace == null ? Workplace.UNKNOWN : workplace;
        seniority = seniority == null ? Seniority.UNKNOWN : seniority;
    }

    public boolean remote() {
        return workplace == Workplace.REMOTE;
    }
}
