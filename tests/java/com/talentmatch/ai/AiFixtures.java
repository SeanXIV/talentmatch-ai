package com.talentmatch.ai;

import com.talentmatch.domain.scoring.MatchEvaluation;
import com.talentmatch.domain.scoring.SkillHit;
import com.talentmatch.repository.MatchJdbcRepository.StoredExplanation;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Builders for AI unit tests (weights 10 required / 5 nice, like the default scoring config). */
final class AiFixtures {

    static final Instant T0 = Instant.parse("2026-10-02T09:00:00Z");

    private AiFixtures() {
    }

    static SkillHit has(String name, Integer years) {
        return new SkillHit(UUID.nameUUIDFromBytes(name.getBytes()), name, years);
    }

    static SkillHit has(String name) {
        return has(name, null);
    }

    static SkillHit miss(String name) {
        return new SkillHit(UUID.nameUUIDFromBytes(name.getBytes()), name, null);
    }

    static MatchEvaluation eval(List<SkillHit> matchedRequired, List<SkillHit> matchedNice,
                                List<SkillHit> missingRequired, List<SkillHit> missingNice) {
        int earned = matchedRequired.size() * 10 + matchedNice.size() * 5;
        int max = earned + missingRequired.size() * 10 + missingNice.size() * 5;
        int r = matchedRequired.size();
        int bigR = r + missingRequired.size();
        int n = matchedNice.size();
        int bigN = n + missingNice.size();
        String summary = "Matches " + r + " of " + bigR + " required skills; " + n + " of " + bigN + " nice-to-have.";
        return new MatchEvaluation(max == 0 ? 0 : (double) earned / max, earned, max,
                matchedRequired, matchedNice, missingRequired, missingNice, summary);
    }

    /** Spec §6 example 1: Java (5 years), SQL required; has Docker, missing Kubernetes (83%). */
    static MatchEvaluation ada() {
        return eval(List.of(has("Java", 5), has("SQL")), List.of(has("Docker")),
                List.of(), List.of(miss("Kubernetes")));
    }

    static JobContext job(String description) {
        return new JobContext(UUID.fromString("00000000-0000-0000-0000-00000000000a"), "Backend Engineer", "Acme",
                description, T0);
    }

    static MatchContext match(int rank, String name, String summary, MatchEvaluation eval, StoredExplanation stored) {
        return new MatchContext(rank, UUID.nameUUIDFromBytes(("cand-" + rank + name).getBytes()), name, summary,
                T0.minus(Duration.ofHours(1)), eval.score(), (int) Math.round(eval.score() * 100), eval, stored);
    }

    static MatchContext match(int rank, String name) {
        return match(rank, name, "Engineer.", ada(), null);
    }

    static AiProperties props(boolean enabled, int topN, Duration budget, Duration backoff) {
        return new AiProperties(enabled, AiProvider.OLLAMA, topN, Duration.ofSeconds(60), budget, 2, 20, 2000,
                backoff, Duration.ofSeconds(60), new AiProperties.Circuit(3, Duration.ofSeconds(30)),
                null, null, null);
    }

    static AiProperties props() {
        return props(true, 5, Duration.ofSeconds(2), Duration.ofSeconds(60));
    }

    static MatchExplanation validExplanation() {
        return new MatchExplanation("Strong fit with every required skill",
                "Ada Lovelace covers both required skills, Java with 5 years of experience and SQL.",
                List.of("Java with 5 years", "SQL"), List.of("Kubernetes (nice-to-have)"));
    }
}
