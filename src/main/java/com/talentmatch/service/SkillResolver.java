package com.talentmatch.service;

import com.talentmatch.domain.entity.Skill;
import com.talentmatch.repository.SkillAliasRepository;
import com.talentmatch.repository.SkillRepository;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Resolves skill names from a request to existing {@link Skill} rows (case-insensitive). A name
 * that is not a skill name but is an alias resolves to the alias's skill ("Postgres" →
 * PostgreSQL); an exact skill name always wins. Skills are never auto-created: unknown and
 * duplicate names (including two names for the same skill) become field errors.
 */
@Component
public class SkillResolver {

    public static final int MAX_SKILL_NAME_LENGTH = 100;

    private final SkillRepository skillRepository;
    private final SkillAliasRepository aliasRepository;

    public SkillResolver(SkillRepository skillRepository, SkillAliasRepository aliasRepository) {
        this.skillRepository = skillRepository;
        this.aliasRepository = aliasRepository;
    }

    /**
     * @param names     normalized names by list index (null = blank, already reported by the caller)
     * @param listField request field holding the list, e.g. {@code skills}
     * @param errors    receives unknown / duplicate errors at {@code skills[i].name}
     * @return resolved skills by list index (only indexes that resolved and are not duplicates)
     */
    public Map<Integer, Skill> resolve(List<String> names, String listField, FieldErrors errors) {
        Map<Integer, Skill> resolved = new LinkedHashMap<>();
        Map<String, Skill> byKey = lookup(names);

        Map<String, Integer> firstIndex = new HashMap<>();
        Map<UUID, Integer> firstIndexBySkill = new HashMap<>();
        for (int i = 0; i < names.size(); i++) {
            String name = names.get(i);
            if (name == null || name.length() > MAX_SKILL_NAME_LENGTH) {
                continue;
            }
            String field = listField + "[" + i + "].name";
            String key = TextNormalizer.skillKey(name);
            Integer first = firstIndex.putIfAbsent(key, i);
            if (first != null) {
                errors.add(field, "Duplicate skill '" + name + "' (already listed at "
                        + listField + "[" + first + "]).");
                continue;
            }
            Skill skill = byKey.get(key);
            if (skill == null) {
                errors.add(field, "Unknown skill '" + name
                        + "'. Check the spelling or create it with POST /api/skills.");
                continue;
            }
            Integer firstForSkill = firstIndexBySkill.putIfAbsent(skill.getId(), i);
            if (firstForSkill != null) {
                errors.add(field, "Duplicate skill '" + name + "': it is another name for '" + skill.getName()
                        + "', already listed at " + listField + "[" + firstForSkill + "].");
                continue;
            }
            resolved.put(i, skill);
        }
        return resolved;
    }

    /** Skill per lower-cased name: exact skill names first, then aliases for the names left over. */
    private Map<String, Skill> lookup(List<String> names) {
        Set<String> keys = new HashSet<>();
        for (String name : names) {
            if (name != null && name.length() <= MAX_SKILL_NAME_LENGTH) {
                keys.add(TextNormalizer.skillKey(name));
            }
        }
        Map<String, Skill> byKey = new HashMap<>();
        if (keys.isEmpty()) {
            return byKey;
        }
        for (Skill s : skillRepository.findAllByLowerNameIn(keys)) {
            byKey.put(TextNormalizer.skillKey(s.getName()), s);
        }
        Set<String> rest = new HashSet<>(keys);
        rest.removeAll(byKey.keySet());
        if (!rest.isEmpty()) {
            Map<String, UUID> aliasSkillIds = aliasRepository.findSkillIdsByLowerAliases(rest);
            if (!aliasSkillIds.isEmpty()) {
                Map<UUID, Skill> skills = new HashMap<>();
                skillRepository.findAllById(new HashSet<>(aliasSkillIds.values()))
                        .forEach(s -> skills.put(s.getId(), s));
                aliasSkillIds.forEach((alias, skillId) -> {
                    Skill s = skills.get(skillId);
                    if (s != null) {
                        byKey.put(alias, s);
                    }
                });
            }
        }
        return byKey;
    }
}
