package com.talentmatch.service;

import com.talentmatch.domain.entity.Skill;
import com.talentmatch.repository.SkillAliasRepository;
import com.talentmatch.repository.SkillRepository;
import com.talentmatch.service.exception.ConflictException;
import com.talentmatch.service.exception.NotFoundException;
import com.talentmatch.web.dto.SkillAliasRequest;
import com.talentmatch.web.dto.SkillAliasResponse;
import com.talentmatch.web.error.ErrorCode;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Skill aliases: other names for a skill ("Postgres" → PostgreSQL). Request skill names resolve
 * through them, and the job feed's skill dictionary matches them. An alias is unique
 * (case-insensitive) and never equals any skill's name; both rules are checked under the
 * vocabulary lock that {@code POST /api/skills} also takes.
 */
@Service
public class SkillAliasService {

    private final SkillRepository skillRepository;
    private final SkillAliasRepository aliasRepository;

    public SkillAliasService(SkillRepository skillRepository, SkillAliasRepository aliasRepository) {
        this.skillRepository = skillRepository;
        this.aliasRepository = aliasRepository;
    }

    @Transactional(readOnly = true)
    public List<SkillAliasResponse> list(UUID skillId) {
        requireSkill(skillId);
        return aliasRepository.findBySkill(skillId).stream().map(SkillAliasService::toResponse).toList();
    }

    @Transactional
    public SkillAliasResponse create(UUID skillId, SkillAliasRequest request) {
        Skill skill = requireSkill(skillId);
        String alias = TextNormalizer.skillName(request == null ? null : request.alias());
        FieldErrors errors = new FieldErrors();
        if (errors.required("alias", alias, "Alias is required.")) {
            errors.maxLength("alias", alias, SkillResolver.MAX_SKILL_NAME_LENGTH, "Alias");
        }
        errors.throwIfAny();

        aliasRepository.lockVocabulary(); // the alias-vs-skill-name rule spans two tables
        skillRepository.findByNameIgnoringCase(alias).ifPresent(existing -> {
            String whose = existing.getId().equals(skill.getId()) ? "this skill's own name" : "the name of skill '"
                    + existing.getName() + "' (id " + existing.getId() + ")";
            throw new ConflictException(ErrorCode.SKILL_ALIAS_ALREADY_EXISTS,
                    "'" + alias + "' can't be an alias: it is " + whose + ".");
        });
        aliasRepository.findByAliasIgnoringCase(alias).ifPresent(existing -> {
            String target = existing.skillId().equals(skill.getId()) ? "this skill"
                    : skillRepository.findById(existing.skillId()).map(s -> "'" + s.getName() + "'")
                            .orElse("another skill") + " (skill id " + existing.skillId() + ")";
            throw new ConflictException(ErrorCode.SKILL_ALIAS_ALREADY_EXISTS,
                    "'" + alias + "' is already an alias of " + target + " (alias id " + existing.id() + ").");
        });
        return toResponse(aliasRepository.insert(skill.getId(), alias));
    }

    @Transactional
    public void delete(UUID skillId, UUID aliasId) {
        requireSkill(skillId);
        if (aliasRepository.delete(skillId, aliasId) == 0) {
            throw NotFoundException.skillAlias(skillId, aliasId);
        }
    }

    private Skill requireSkill(UUID skillId) {
        return skillRepository.findById(skillId).orElseThrow(() -> NotFoundException.skill(skillId));
    }

    private static SkillAliasResponse toResponse(SkillAliasRepository.AliasRow row) {
        return new SkillAliasResponse(row.id(), row.alias(), row.createdAt());
    }
}
