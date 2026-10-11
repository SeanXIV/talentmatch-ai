package com.talentmatch.web.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.talentmatch.feed.FeedJobView;
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
 * A feed job (§5.2). {@code description} is returned by {@code GET /api/feed/jobs/{id}} only (omitted
 * from lists, and when the job has no description yet). {@code score} is rounded to 4 decimals.
 */
public record FeedJobResponse(UUID jobId, String title, String company, String primaryUrl, Instant firstSeenAt,
                              Instant postedAt, Instant closedAt, boolean baseline, Workplace workplace,
                              String locationText, List<String> countryCodes, Seniority seniority, Salary salary,
                              Double score, Integer scorePercent, boolean matchable, String summary,
                              List<String> matchedRequired, List<String> missingRequired, String preferenceVerdict,
                              List<String> filterReasons, List<String> flags, List<Skill> skills,
                              List<AiSuggestion> aiSuggestions, String enrichmentStatus, Notification notification,
                              List<Source> sources,
                              @JsonInclude(JsonInclude.Include.NON_NULL) String description) {

    public record Salary(BigDecimal min, BigDecimal max, String currency, SalaryPeriod period, boolean estimated) {
    }

    /** {@code source}: DICTIONARY, AI or BOTH (the extraction stage that found the skill). */
    public record Skill(String name, boolean required, EffectiveSkills.Source source) {
    }

    public record AiSuggestion(String name, String requirement, String evidence) {
    }

    public record Notification(String status, Channel channel, Instant sentAt) {
    }

    public record Source(SourceKind kind, String via, String url, String externalId, Instant firstSeenAt,
                         Instant closedAt) {
    }

    public static FeedJobResponse of(FeedJobView v) {
        FeedJobView.Salary s = v.salary();
        FeedJobView.Notification n = v.notification();
        return new FeedJobResponse(v.jobId(), v.title(), v.company(), v.primaryUrl(), v.firstSeenAt(), v.postedAt(),
                v.closedAt(), v.baseline(), v.workplace(), v.locationText(), v.countryCodes(), v.seniority(),
                s == null ? null : new Salary(s.min(), s.max(), s.currency(), s.period(), s.estimated()),
                v.score() == null ? null : Math.round(v.score() * 10_000d) / 10_000d,
                v.scorePercent(), v.matchable(), v.summary(), v.matchedRequired(), v.missingRequired(),
                v.preferenceVerdict(), v.filterReasons(), v.flags(),
                v.skills().stream().map(k -> new Skill(k.name(), k.required(), k.source())).toList(),
                v.aiSuggestions().stream().map(FeedJobResponse::suggestion).toList(),
                v.enrichmentStatus(),
                n == null ? null : new Notification(n.status(), n.channel(), n.sentAt()),
                v.sources().stream().map(o -> new Source(o.kind(), o.via(), o.url(), o.externalId(), o.firstSeenAt(),
                        o.closedAt())).toList(),
                v.description());
    }

    private static AiSuggestion suggestion(SkillSuggestion s) {
        return new AiSuggestion(s.name(), s.requirement(), s.evidence());
    }
}
