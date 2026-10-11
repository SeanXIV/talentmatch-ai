package com.talentmatch.feed;

import com.talentmatch.preferences.SalaryPeriod;
import com.talentmatch.preferences.Seniority;
import com.talentmatch.preferences.Workplace;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * A posting ready to store in {@code job_posting} (V5): every value fits its column and its CHECK.
 * Built only by {@link PostingNormalizer}.
 *
 * @param description         cleaned plain text (at most 20000 characters); null while pending
 *                            (Greenhouse listing without detail)
 * @param descriptionComplete false for a missing description or a snippet (aggregator)
 * @param countryCode         ISO 3166 alpha-2 (upper case), or null
 * @param sourceUpdatedAt     the provider's last-update time, if it has one
 * @param contentVersion      the provider's change marker (Greenhouse {@code updated_at}); not stored
 *                            (equal to {@code sourceUpdatedAt} where it exists)
 * @param seniority           from the title
 * @param contentHash         sha256 (lower-case hex) of the stored content fields; equal hashes mean
 *                            "unchanged"
 */
public record NormalizedPosting(
        String externalId,
        String url,
        String title,
        String company,
        String description,
        boolean descriptionComplete,
        String locationText,
        String countryCode,
        Workplace workplace,
        String employmentType,
        BigDecimal salaryMin,
        BigDecimal salaryMax,
        String salaryCurrency,
        SalaryPeriod salaryPeriod,
        boolean salaryEstimated,
        Instant postedAt,
        Instant sourceUpdatedAt,
        Instant contentVersion,
        Seniority seniority,
        String contentHash) {
}
