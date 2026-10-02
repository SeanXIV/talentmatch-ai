package com.talentmatch.domain.scoring;

import java.util.Objects;
import java.util.UUID;

/**
 * One skill a candidate has.
 *
 * @param skillId         skill id
 * @param yearsExperience years of experience, or null if unknown (never affects the score)
 */
public record CandidateSkillFact(UUID skillId, Integer yearsExperience) {

    public CandidateSkillFact {
        Objects.requireNonNull(skillId, "skillId");
    }
}
