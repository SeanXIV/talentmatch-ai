package com.talentmatch.feed.skills;

import java.util.UUID;

/**
 * A dictionary skill found in a posting.
 *
 * @param skillId  the skill (also when it was found through an alias)
 * @param name     the skill's own name
 * @param required false when the first counted mention is nice-to-have ({@link RequirementHeuristic})
 * @param offset   where the first counted mention starts in the matched text
 */
public record SkillMention(UUID skillId, String name, boolean required, int offset) {

    public SkillRequirement toRequirement() {
        return new SkillRequirement(skillId, name, required);
    }
}
