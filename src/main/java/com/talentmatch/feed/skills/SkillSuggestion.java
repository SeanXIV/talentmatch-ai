package com.talentmatch.feed.skills;

/**
 * A skill the AI found in a posting that is neither a skill nor an alias (§4.7). Shown to the owner,
 * never created automatically. The JSON shape of the items in feed_job.ai_suggestions (V5).
 *
 * @param name        the name as the posting writes it
 * @param requirement REQUIRED or NICE_TO_HAVE
 * @param evidence    the shortest phrase of the posting that names it
 */
public record SkillSuggestion(String name, String requirement, String evidence) {
}
