package com.talentmatch.domain.scoring;

import java.util.Objects;
import java.util.UUID;

/**
 * One skill listed on a job.
 *
 * @param skillId  skill id
 * @param name     display name of the skill
 * @param required true for must-have, false for nice-to-have
 */
public record JobRequirement(UUID skillId, String name, boolean required) {

    public JobRequirement {
        Objects.requireNonNull(skillId, "skillId");
        Objects.requireNonNull(name, "name");
    }
}
