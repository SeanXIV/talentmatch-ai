package com.talentmatch.feed;

import com.talentmatch.feed.skills.EffectiveSkills;
import com.talentmatch.feed.skills.SkillSuggestion;
import com.talentmatch.feed.source.SourceKind;
import com.talentmatch.notify.Channel;
import com.talentmatch.preferences.SalaryPeriod;
import com.talentmatch.preferences.Seniority;
import com.talentmatch.preferences.Workplace;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One feed job for {@code GET /api/feed/jobs[/{id}]} (§5.2).
 *
 * @param salary            null when the posting states no salary
 * @param score             the owner's cached score (job_match); null without an owner profile, before
 *                          the job was processed, or when the job has no skills
 * @param scorePercent      {@code score} as a whole percentage
 * @param matchable         the job has at least one skill
 * @param summary           the scoring summary for the owner, computed now from the job's skills and the
 *                          owner's skills; null without an owner profile or skills
 * @param matchedRequired   required skills the owner has (names)
 * @param missingRequired   required skills the owner lacks (names)
 * @param preferenceVerdict PASS, FILTERED, or null before the first processing
 * @param filterReasons     why it was filtered ({@code PreferenceFilter.Reason} names)
 * @param flags             things to check ({@code PreferenceFilter.Flag} names)
 * @param skills            the effective skills, with the stage that found each
 * @param notification      the job's notification, or null
 * @param sources           every posting of the job (open and closed), oldest first
 * @param description       the job description; detail view only (null in lists)
 */
public record FeedJobView(UUID jobId, String title, String company, String primaryUrl, Instant firstSeenAt,
                          Instant postedAt, Instant closedAt, boolean baseline, Workplace workplace, String locationText,
                          List<String> countryCodes, Seniority seniority, Salary salary, Double score,
                          Integer scorePercent, boolean matchable, String summary, List<String> matchedRequired,
                          List<String> missingRequired, String preferenceVerdict, List<String> filterReasons,
                          List<String> flags, List<Skill> skills, List<SkillSuggestion> aiSuggestions,
                          String enrichmentStatus, Notification notification, List<Source> sources,
                          String description) {

    public record Salary(BigDecimal min, BigDecimal max, String currency, SalaryPeriod period, boolean estimated) {
    }

    public record Skill(String name, boolean required, EffectiveSkills.Source source) {
    }

    public record Notification(String status, Channel channel, Instant sentAt) {
    }

    /**
     * @param via the provider's display name ("Greenhouse", "Lever", "Ashby", "Adzuna"; attribution)
     */
    public record Source(SourceKind kind, String via, String url, String externalId, Instant firstSeenAt,
                         Instant closedAt) {
    }
}
