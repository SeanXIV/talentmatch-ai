package com.talentmatch.seeker;

import com.talentmatch.ai.ExplanationStatus;
import com.talentmatch.ai.ExplanationView;
import com.talentmatch.preferences.SalaryPeriod;
import com.talentmatch.preferences.Seniority;
import com.talentmatch.preferences.Workplace;
import com.talentmatch.service.MatchBreakdownView;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One ranked job for a candidate (§5.1). Feed-only fields ({@code primaryUrl}, {@code firstSeenAt},
 * {@code postedAt}, {@code closedAt}, {@code baseline}, {@code workplace}, {@code locationText},
 * {@code seniority}, {@code salary}, {@code enrichmentStatus}) are null for MANUAL jobs, and
 * {@code countryCodes} is empty.
 *
 * @param rank              1-based rank across pages (page * limit + index + 1)
 * @param origin            MANUAL or FEED
 * @param listedAt          the job's listing date (decision d); null when {@code listedAtSource} is UNKNOWN
 * @param salary            null when the posting states no salary (or for MANUAL jobs)
 * @param preferenceVerdict owner only: PASS, FILTERED or null
 * @param filterReasons     owner only: why the owner's preferences filtered it out
 * @param flags             owner only: things to check
 * @param score             cached score in [0, 1], rounded to 4 decimals
 * @param scorePercent      score as a whole percentage
 * @param aiExplanation     current AI explanation text; non-null only when READY
 * @param explanationStatus READY, PENDING, STALE or UNAVAILABLE
 * @param explanation       always present: the seeker AI explanation, or a template built from the breakdown
 * @param computedAt        when the score was last verified fresh
 */
public record CandidateMatchView(
        int rank,
        UUID jobId,
        String title,
        String company,
        String origin,
        Instant listedAt,
        ListedAtSource listedAtSource,
        Instant postedAt,
        Instant firstSeenAt,
        Instant closedAt,
        Boolean baseline,
        String primaryUrl,
        Workplace workplace,
        String locationText,
        List<String> countryCodes,
        Seniority seniority,
        Salary salary,
        String enrichmentStatus,
        String preferenceVerdict,
        List<String> filterReasons,
        List<String> flags,
        double score,
        int scorePercent,
        String summary,
        MatchBreakdownView breakdown,
        String aiExplanation,
        ExplanationStatus explanationStatus,
        ExplanationView explanation,
        Instant computedAt) {

    /** A feed job's salary as posted (or estimated by the provider). */
    public record Salary(BigDecimal min, BigDecimal max, String currency, SalaryPeriod period, boolean estimated) {
    }
}
