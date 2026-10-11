package com.talentmatch.feed.skills;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * The skills a feed job ends up with (pure): the union of the dictionary and AI stages. For
 * {@code required}, the AI's classification wins when the AI found the skill; otherwise the
 * dictionary heuristic's value. The result is sorted (name, then id), so merging again gives the
 * same list and the job_skill diff writes nothing.
 */
public final class EffectiveSkills {

    /** Which stage found a skill. */
    public enum Source {
        DICTIONARY,
        AI,
        BOTH
    }

    public record EffectiveSkill(UUID skillId, String name, boolean required, Source source) {
    }

    private static final Comparator<EffectiveSkill> ORDER = Comparator
            .comparing((EffectiveSkill s) -> s.name() == null ? "" : s.name().toLowerCase(Locale.ROOT))
            .thenComparing(EffectiveSkill::skillId);

    private EffectiveSkills() {
    }

    /**
     * @param dictionary dictionary-stage skills (null = none)
     * @param ai         AI-stage skills (null = not enriched)
     */
    public static List<EffectiveSkill> merge(List<SkillRequirement> dictionary, List<SkillRequirement> ai) {
        Map<UUID, SkillRequirement> dict = collapse(dictionary);
        Map<UUID, SkillRequirement> fromAi = collapse(ai);
        List<EffectiveSkill> out = new ArrayList<>(dict.size() + fromAi.size());
        for (SkillRequirement d : dict.values()) {
            SkillRequirement a = fromAi.get(d.skillId());
            out.add(a == null
                    ? new EffectiveSkill(d.skillId(), d.name(), d.required(), Source.DICTIONARY)
                    : new EffectiveSkill(d.skillId(), d.name(), a.required(), Source.BOTH));
        }
        for (SkillRequirement a : fromAi.values()) {
            if (!dict.containsKey(a.skillId())) {
                out.add(new EffectiveSkill(a.skillId(), a.name(), a.required(), Source.AI));
            }
        }
        out.sort(ORDER);
        return List.copyOf(out);
    }

    /** skill id → required, in the merged order (what job_skill should hold). */
    public static Map<UUID, Boolean> requiredBySkill(List<EffectiveSkill> skills) {
        Map<UUID, Boolean> out = new LinkedHashMap<>();
        skills.forEach(s -> out.put(s.skillId(), s.required()));
        return out;
    }

    /** One entry per skill id; a skill listed twice is required if any entry says so. */
    private static Map<UUID, SkillRequirement> collapse(List<SkillRequirement> list) {
        Map<UUID, SkillRequirement> out = new LinkedHashMap<>();
        if (list == null) {
            return out;
        }
        for (SkillRequirement r : list) {
            if (r == null || r.skillId() == null) {
                continue;
            }
            out.merge(r.skillId(), r, (x, y) -> new SkillRequirement(x.skillId(), x.name(),
                    x.required() || y.required()));
        }
        return out;
    }
}
