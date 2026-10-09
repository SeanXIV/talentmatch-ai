package com.talentmatch.feed;

import com.talentmatch.feed.skills.DictionarySkillMatcher;
import com.talentmatch.feed.skills.EffectiveSkills;
import com.talentmatch.feed.skills.SkillMention;
import com.talentmatch.feed.skills.SkillRequirement;
import com.talentmatch.preferences.FeedFacts;
import com.talentmatch.preferences.JobPreferences;
import com.talentmatch.preferences.PreferenceFilter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Steps 1–3 of processing one feed job (§1.2), pure: dictionary skills from the title and
 * description, merged with the AI skills into the job's effective skills, and the preference
 * verdict. Deterministic: the same inputs always give the same lists in the same order, so
 * processing a job twice writes nothing the second time.
 */
public final class FeedEvaluation {

    private FeedEvaluation() {
    }

    /**
     * @param dictionarySkills what the dictionary found (stored in feed_job.dictionary_skills)
     * @param skills           the effective skills (what job_skill must hold), sorted by name then id
     * @param verdict          the preference filter's verdict
     */
    public record Result(List<SkillRequirement> dictionarySkills, List<EffectiveSkills.EffectiveSkill> skills,
                         PreferenceFilter.Verdict verdict) {

        public Result {
            dictionarySkills = List.copyOf(dictionarySkills);
            skills = List.copyOf(skills);
        }

        /** skill id → required, for the job_skill diff. */
        public Map<UUID, Boolean> jobSkills() {
            return EffectiveSkills.requiredBySkill(skills);
        }

        /** No skills means the job can't be scored or notified (§4.6). */
        public boolean matchable() {
            return !skills.isEmpty();
        }
    }

    /**
     * @param title       the job title (canonical posting)
     * @param description the job description, or null while it is pending
     * @param aiSkills    AI-stage skills, or null when the job was not enriched
     * @param matcher     the dictionary of the current skill vocabulary
     * @param skillNames  every current skill id → name; AI skills of deleted skills are dropped and the
     *                    others take the current name (a skill may have been renamed)
     * @param facts       the posting facts for the preference filter
     * @param preferences the owner's preferences; null = none saved (no filters)
     */
    public static Result evaluate(String title, String description, List<SkillRequirement> aiSkills,
                                  DictionarySkillMatcher matcher, Map<UUID, String> skillNames, FeedFacts facts,
                                  JobPreferences preferences) {
        List<SkillRequirement> dictionary = new ArrayList<>();
        for (SkillMention m : matcher.match(title, description)) {
            dictionary.add(m.toRequirement());
        }
        List<SkillRequirement> ai = null;
        if (aiSkills != null) {
            ai = new ArrayList<>(aiSkills.size());
            for (SkillRequirement s : aiSkills) {
                String current = s == null || s.skillId() == null ? null : skillNames.get(s.skillId());
                if (current != null) {
                    ai.add(new SkillRequirement(s.skillId(), current, s.required()));
                }
            }
        }
        List<EffectiveSkills.EffectiveSkill> skills = EffectiveSkills.merge(dictionary, ai);
        PreferenceFilter.Verdict verdict = PreferenceFilter.evaluate(facts,
                preferences == null ? JobPreferences.none() : preferences);
        return new Result(dictionary, skills, verdict);
    }
}
