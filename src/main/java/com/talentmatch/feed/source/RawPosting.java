package com.talentmatch.feed.source;

import com.talentmatch.preferences.SalaryPeriod;
import com.talentmatch.preferences.Workplace;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * One posting as a provider describes it, before normalization (lengths, HTML to text, seniority
 * are the poller's job). Strings are stripped, never blank (blank becomes null).
 *
 * @param externalId            the provider's id, unique within the source
 * @param url                   the public http(s) URL of the posting
 * @param company               the company name; never null
 * @param descriptionHtmlOrText null when the listing has no description (Greenhouse list)
 * @param descriptionIsHtml     {@code descriptionHtmlOrText} is HTML (already entity-unescaped)
 * @param descriptionComplete   false when the description is missing or a snippet
 * @param countryCode           ISO 3166 alpha-2, or null when unknown
 * @param workplace             never null; {@link Workplace#UNKNOWN} when the provider doesn't say
 * @param salaryPeriod          null when the provider gives amounts without a period (Greenhouse)
 * @param postedAt              the provider's first publish time; null when it has none
 * @param sourceUpdatedAt       the provider's last-update time, when it has one
 * @param contentVersion        changes whenever the posting content changes (Greenhouse
 *                              {@code updated_at}); null when the provider has no such field
 */
public record RawPosting(String externalId, String url, String title, String company, String descriptionHtmlOrText,
                         boolean descriptionIsHtml, boolean descriptionComplete, String locationText,
                         String countryCode, Workplace workplace, String employmentType, BigDecimal salaryMin,
                         BigDecimal salaryMax, String salaryCurrency, SalaryPeriod salaryPeriod,
                         boolean salaryEstimated, Instant postedAt, Instant sourceUpdatedAt, Instant contentVersion) {

    public RawPosting {
        workplace = workplace == null ? Workplace.UNKNOWN : workplace;
    }
}
