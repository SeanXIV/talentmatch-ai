package com.talentmatch.domain.scoring;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Pure, deterministic skill-overlap scoring (no Spring, no JPA).
 *
 * <p>Each job skill is worth {@link ScoringWeights#required()} points if required and
 * {@link ScoringWeights#niceToHave()} points otherwise. A candidate earns a skill's points if
 * they have it. {@code score = earned / max}, clamped to [0, 1]. Extra candidate skills are
 * ignored; years of experience never change the score (they are only reported on hits).
 */
public final class ScoringEngine {

    private static final Comparator<SkillHit> HIT_ORDER = Comparator
            .comparing((SkillHit h) -> h.name().toLowerCase(Locale.ROOT))
            .thenComparing(SkillHit::skillId);

    private final ScoringWeights weights;

    public ScoringEngine(ScoringWeights weights) {
        this.weights = Objects.requireNonNull(weights, "weights");
    }

    public ScoringWeights weights() {
        return weights;
    }

    /** A job can be scored only if it lists at least one skill. */
    public static boolean isMatchable(Collection<JobRequirement> reqs) {
        return reqs != null && !reqs.isEmpty();
    }

    /**
     * Scores one candidate against a job's skills.
     *
     * @param reqs            the job's skills (non-empty, unique skill ids)
     * @param candidateSkills the candidate's skills (may be empty; null treated as empty)
     * @return the evaluation with sorted breakdown lists and a summary
     * @throws IllegalArgumentException if reqs is empty or contains duplicate skill ids
     */
    public MatchEvaluation evaluate(List<JobRequirement> reqs, Collection<CandidateSkillFact> candidateSkills) {
        if (!isMatchable(reqs)) {
            throw new IllegalArgumentException("A job needs at least one skill to be scored");
        }
        Set<UUID> seen = new HashSet<>(reqs.size() * 2);
        for (JobRequirement r : reqs) {
            Objects.requireNonNull(r, "requirement");
            if (!seen.add(r.skillId())) {
                throw new IllegalArgumentException("Duplicate job skill id " + r.skillId());
            }
        }

        Map<UUID, CandidateSkillFact> have = new HashMap<>();
        if (candidateSkills != null) {
            for (CandidateSkillFact f : candidateSkills) {
                if (f != null) {
                    have.merge(f.skillId(), f, ScoringEngine::keepMoreYears);
                }
            }
        }

        List<SkillHit> matchedRequired = new ArrayList<>();
        List<SkillHit> matchedNice = new ArrayList<>();
        List<SkillHit> missingRequired = new ArrayList<>();
        List<SkillHit> missingNice = new ArrayList<>();
        int earned = 0;
        int max = 0;
        for (JobRequirement r : reqs) {
            int points = r.required() ? weights.required() : weights.niceToHave();
            max += points;
            CandidateSkillFact fact = have.get(r.skillId());
            if (fact != null) {
                earned += points;
                SkillHit hit = new SkillHit(r.skillId(), r.name(), fact.yearsExperience());
                (r.required() ? matchedRequired : matchedNice).add(hit);
            } else {
                SkillHit miss = new SkillHit(r.skillId(), r.name(), null);
                (r.required() ? missingRequired : missingNice).add(miss);
            }
        }
        matchedRequired.sort(HIT_ORDER);
        matchedNice.sort(HIT_ORDER);
        missingRequired.sort(HIT_ORDER);
        missingNice.sort(HIT_ORDER);

        double score = max == 0 ? 0.0 : Math.min(1.0, Math.max(0.0, earned / (double) max));
        String summary = summary(matchedRequired.size(), matchedRequired.size() + missingRequired.size(),
                matchedNice.size(), matchedNice.size() + missingNice.size(), missingRequired);
        return new MatchEvaluation(score, earned, max, matchedRequired, matchedNice,
                missingRequired, missingNice, summary);
    }

    private static CandidateSkillFact keepMoreYears(CandidateSkillFact a, CandidateSkillFact b) {
        if (a.yearsExperience() == null) {
            return b;
        }
        if (b.yearsExperience() == null) {
            return a;
        }
        return b.yearsExperience() > a.yearsExperience() ? b : a;
    }

    /**
     * Builds e.g. "Matches 4 of 5 required skills; missing: Kubernetes; 1 of 2 nice-to-have."
     */
    static String summary(int r, int requiredCount, int n, int niceCount, List<SkillHit> missingRequired) {
        List<String> parts = new ArrayList<>(3);
        if (requiredCount > 0) {
            parts.add("Matches " + r + " of " + requiredCount + " required " + noun(requiredCount));
            if (r < requiredCount) {
                parts.add("missing: " + missingRequired.stream().map(SkillHit::name)
                        .collect(Collectors.joining(", ")));
            }
            if (niceCount > 0) {
                parts.add(n + " of " + niceCount + " nice-to-have");
            }
        } else if (niceCount > 0) {
            parts.add("Matches " + n + " of " + niceCount + " nice-to-have " + noun(niceCount));
        }
        return String.join("; ", parts) + ".";
    }

    private static String noun(int count) {
        return count == 1 ? "skill" : "skills";
    }
}
