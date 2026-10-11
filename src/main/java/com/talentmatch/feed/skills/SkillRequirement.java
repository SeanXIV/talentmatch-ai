package com.talentmatch.feed.skills;

import java.util.UUID;

/**
 * A skill a feed job asks for, from one extraction stage. The JSON shape of the items in
 * feed_job.dictionary_skills and feed_job.ai_skills (V5).
 */
public record SkillRequirement(UUID skillId, String name, boolean required) {
}
