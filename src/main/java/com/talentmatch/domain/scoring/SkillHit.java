package com.talentmatch.domain.scoring;

import java.util.UUID;

/**
 * A job skill in a match breakdown.
 *
 * @param skillId         skill id
 * @param name            skill name
 * @param yearsExperience candidate's years for matched skills; always null for missing skills
 */
public record SkillHit(UUID skillId, String name, Integer yearsExperience) {
}
