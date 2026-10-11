package com.talentmatch.feed;

import com.talentmatch.domain.scoring.CandidateSkillFact;
import com.talentmatch.domain.scoring.JobRequirement;
import com.talentmatch.domain.scoring.MatchEvaluation;
import com.talentmatch.domain.scoring.ScoringEngine;
import com.talentmatch.domain.scoring.SkillHit;
import com.talentmatch.feed.skills.EffectiveSkills;
import com.talentmatch.feed.skills.SkillRequirement;
import com.talentmatch.notify.Channel;
import com.talentmatch.profile.OwnerProfileRepository;
import com.talentmatch.repository.MatchJdbcRepository;
import com.talentmatch.service.Paging;
import com.talentmatch.service.exception.NotFoundException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads feed jobs for {@code GET /api/feed/jobs} and {@code GET /api/feed/jobs/{id}} (§5.2). The
 * owner's score is the cached {@code job_match} row the processor wrote; the summary and the
 * matched / missing skills are recomputed with {@link ScoringEngine} from the current skills, as
 * {@code GET /api/jobs/{id}/matches} does for its breakdown. Read-only; nothing is scored here.
 */
@Service
public class FeedJobService {

    /** Filters of the list (defaults hide filtered, closed and baseline jobs). */
    public record Filters(Instant since, Double minScore, boolean includeFiltered, boolean includeClosed,
                          boolean includeBaseline) {
    }

    private final FeedJobRepository jobs;
    private final OwnerProfileRepository profiles;
    private final MatchJdbcRepository matches;
    private final ScoringEngine engine;

    public FeedJobService(FeedJobRepository jobs, OwnerProfileRepository profiles, MatchJdbcRepository matches,
                          ScoringEngine engine) {
        this.jobs = jobs;
        this.profiles = profiles;
        this.matches = matches;
        this.engine = engine;
    }

    /** Newest first seen first. */
    @Transactional(readOnly = true)
    public Page<FeedJobView> list(Filters filters, int page, int size) {
        Pageable pageable = Paging.of(page, size);
        UUID owner = profiles.findRef().map(OwnerProfileRepository.OwnerRef::candidateId).orElse(null);
        FeedJobRepository.ListQuery query = new FeedJobRepository.ListQuery(filters.since(), filters.minScore(),
                filters.includeFiltered(), filters.includeClosed(), filters.includeBaseline(), owner);
        long total = jobs.count(query);
        List<FeedJobRepository.JobRow> rows = total <= pageable.getOffset() ? List.of()
                : jobs.findPage(query, pageable.getOffset(), pageable.getPageSize());
        return new PageImpl<>(assemble(rows, owner), pageable, total);
    }

    /**
     * One feed job with its description.
     *
     * @throws NotFoundException 404 JOB_NOT_FOUND when no job has this id, or it isn't a feed job
     */
    @Transactional(readOnly = true)
    public FeedJobView get(UUID jobId) {
        UUID owner = profiles.findRef().map(OwnerProfileRepository.OwnerRef::candidateId).orElse(null);
        FeedJobRepository.JobRow row = jobs.findJob(jobId, owner).orElseThrow(() -> jobs.jobExists(jobId)
                ? NotFoundException.notFeedJob(jobId) : NotFoundException.job(jobId));
        return assemble(List.of(row), owner).get(0);
    }

    // ------------------------------------------------------------------ assembly

    private List<FeedJobView> assemble(List<FeedJobRepository.JobRow> rows, UUID owner) {
        if (rows.isEmpty()) {
            return List.of();
        }
        Set<UUID> ids = new LinkedHashSet<>();
        rows.forEach(r -> ids.add(r.jobId()));
        Map<UUID, List<FeedJobRepository.SkillRow>> skillsByJob = new HashMap<>();
        Set<UUID> skillIds = new HashSet<>();
        for (FeedJobRepository.SkillRow s : jobs.findSkills(ids)) {
            skillsByJob.computeIfAbsent(s.jobId(), k -> new ArrayList<>()).add(s);
            skillIds.add(s.skillId());
        }
        Map<UUID, List<FeedJobView.Source>> sourcesByJob = new HashMap<>();
        for (FeedJobRepository.PostingRow p : jobs.findPostings(ids)) {
            sourcesByJob.computeIfAbsent(p.jobId(), k -> new ArrayList<>()).add(new FeedJobView.Source(p.kind(),
                    FeedSourceService.display(p.kind()), p.url(), p.externalId(), p.firstSeenAt(), p.closedAt()));
        }
        List<CandidateSkillFact> ownerSkills = owner == null || skillIds.isEmpty() ? List.of()
                : matches.findFacts(skillIds, List.of(owner)).getOrDefault(owner, List.of());

        List<FeedJobView> out = new ArrayList<>(rows.size());
        for (FeedJobRepository.JobRow r : rows) {
            out.add(view(r, skillsByJob.getOrDefault(r.jobId(), List.of()),
                    sourcesByJob.getOrDefault(r.jobId(), List.of()), owner != null, ownerSkills));
        }
        return out;
    }

    private FeedJobView view(FeedJobRepository.JobRow r, List<FeedJobRepository.SkillRow> skillRows,
                             List<FeedJobView.Source> sources, boolean hasOwner, List<CandidateSkillFact> ownerSkills) {
        Set<UUID> fromDictionary = ids(FeedJobJson.skills(r.dictionarySkillsJson()));
        Set<UUID> fromAi = ids(FeedJobJson.skills(r.aiSkillsJson()));
        List<FeedJobView.Skill> skills = new ArrayList<>(skillRows.size());
        List<JobRequirement> reqs = new ArrayList<>(skillRows.size());
        for (FeedJobRepository.SkillRow s : skillRows) {
            skills.add(new FeedJobView.Skill(s.name(), s.required(), source(s.skillId(), fromDictionary, fromAi)));
            reqs.add(new JobRequirement(s.skillId(), s.name(), s.required()));
        }
        boolean matchable = ScoringEngine.isMatchable(reqs);
        MatchEvaluation evaluation = hasOwner && matchable ? engine.evaluate(reqs, ownerSkills) : null;
        Double score = matchable ? r.score() : null;

        FeedJobView.Salary salary = r.salaryMin() == null && r.salaryMax() == null ? null
                : new FeedJobView.Salary(r.salaryMin(), r.salaryMax(), r.salaryCurrency(), r.salaryPeriod(),
                        r.salaryEstimated());
        FeedJobView.Notification notification = r.notificationStatus() == null ? null
                : new FeedJobView.Notification(r.notificationStatus(), channel(r.notificationChannel()),
                        r.notificationSentAt());
        return new FeedJobView(r.jobId(), r.title(), r.company(), r.primaryUrl(), r.firstSeenAt(), r.postedAt(),
                r.closedAt(), r.baseline(), r.workplace(), r.locationText(), r.countryCodes(), r.seniority(), salary,
                score, score == null ? null : (int) Math.round(score * 100d), matchable,
                evaluation == null ? null : evaluation.summary(),
                evaluation == null ? List.of() : names(evaluation.matchedRequired()),
                evaluation == null ? List.of() : names(evaluation.missingRequired()),
                r.preferenceVerdict(), FeedJobJson.strings(r.filterReasonsJson()),
                FeedJobJson.strings(r.filterFlagsJson()), List.copyOf(skills),
                FeedJobJson.suggestions(r.aiSuggestionsJson()), r.enrichmentStatus(), notification,
                List.copyOf(sources), r.description());
    }

    private static EffectiveSkills.Source source(UUID skillId, Set<UUID> dictionary, Set<UUID> ai) {
        boolean d = dictionary.contains(skillId);
        boolean a = ai.contains(skillId);
        if (d && a) {
            return EffectiveSkills.Source.BOTH;
        }
        return a ? EffectiveSkills.Source.AI : EffectiveSkills.Source.DICTIONARY;
    }

    private static Set<UUID> ids(List<SkillRequirement> skills) {
        Set<UUID> ids = new HashSet<>();
        if (skills != null) {
            skills.forEach(s -> {
                if (s.skillId() != null) {
                    ids.add(s.skillId());
                }
            });
        }
        return ids;
    }

    private static List<String> names(List<SkillHit> hits) {
        return hits.stream().map(SkillHit::name).toList();
    }

    private static Channel channel(String value) {
        if (value == null) {
            return null;
        }
        try {
            return Channel.valueOf(value.strip());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
