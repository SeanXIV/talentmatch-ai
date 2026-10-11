package com.talentmatch.service;

import com.talentmatch.domain.entity.Skill;
import com.talentmatch.repository.SkillAliasRepository;
import com.talentmatch.repository.SkillRepository;
import com.talentmatch.service.exception.ConflictException;
import com.talentmatch.service.exception.NotFoundException;
import com.talentmatch.web.dto.PageResponse;
import com.talentmatch.web.dto.SkillRequest;
import com.talentmatch.web.dto.SkillResponse;
import com.talentmatch.web.error.ErrorCode;
import java.util.Locale;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Skill vocabulary: list/search, read, create (no update/delete in Phase 2). */
@Service
public class SkillService {

    public static final int MAX_CATEGORY_LENGTH = 100;

    private final SkillRepository skillRepository;
    private final SkillAliasRepository aliasRepository;

    public SkillService(SkillRepository skillRepository, SkillAliasRepository aliasRepository) {
        this.skillRepository = skillRepository;
        this.aliasRepository = aliasRepository;
    }

    @Transactional(readOnly = true)
    public PageResponse<SkillResponse> list(String q, int page, int size) {
        String query = TextNormalizer.skillName(q);
        Page<Skill> result = query == null
                ? skillRepository.findListPage(Paging.of(page, size))
                : skillRepository.searchListPage(likePattern(query), Paging.of(page, size));
        return PageResponse.of(result, SkillService::toResponse);
    }

    @Transactional(readOnly = true)
    public SkillResponse get(UUID id) {
        return skillRepository.findById(id).map(SkillService::toResponse)
                .orElseThrow(() -> NotFoundException.skill(id));
    }

    @Transactional
    public SkillResponse create(SkillRequest request) {
        if (request == null) {
            request = new SkillRequest(null, null);
        }
        String name = TextNormalizer.skillName(request.name());
        String category = TextNormalizer.text(request.category());

        FieldErrors errors = new FieldErrors();
        if (errors.required("name", name, "Skill name is required.")) {
            errors.maxLength("name", name, SkillResolver.MAX_SKILL_NAME_LENGTH, "Skill name");
        }
        errors.maxLength("category", category, MAX_CATEGORY_LENGTH, "Category");
        errors.throwIfAny();

        aliasRepository.lockVocabulary(); // a skill name must never equal an alias (V5, skill_alias)
        skillRepository.findByNameIgnoringCase(name).ifPresent(existing -> {
            throw new ConflictException(ErrorCode.SKILL_ALREADY_EXISTS,
                    "A skill named '" + name + "' already exists as '" + existing.getName()
                            + "' (id " + existing.getId() + ").");
        });
        aliasRepository.findByAliasIgnoringCase(name).ifPresent(alias -> {
            String target = skillRepository.findById(alias.skillId()).map(Skill::getName).orElse("another skill");
            throw new ConflictException(ErrorCode.SKILL_ALREADY_EXISTS,
                    "'" + name + "' is already another name (alias) for '" + target + "' (skill id "
                            + alias.skillId() + "). Use that skill, or delete the alias first.");
        });
        Skill saved = skillRepository.saveAndFlush(new Skill(name, category));
        return toResponse(saved);
    }

    static SkillResponse toResponse(Skill s) {
        return new SkillResponse(s.getId(), s.getName(), s.getCategory());
    }

    /** Lowercase substring LIKE pattern, escaping %, _ and the escape char '!'. */
    static String likePattern(String query) {
        String lower = query.toLowerCase(Locale.ROOT);
        StringBuilder sb = new StringBuilder(lower.length() + 2).append('%');
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            if (c == '%' || c == '_' || c == '!') {
                sb.append('!');
            }
            sb.append(c);
        }
        return sb.append('%').toString();
    }
}
