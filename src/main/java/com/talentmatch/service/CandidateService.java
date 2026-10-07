package com.talentmatch.service;

import com.talentmatch.domain.entity.Candidate;
import com.talentmatch.domain.entity.CandidateSkill;
import com.talentmatch.domain.entity.Skill;
import com.talentmatch.repository.CandidateRepository;
import com.talentmatch.repository.projection.CandidateListRow;
import com.talentmatch.service.exception.ConflictException;
import com.talentmatch.service.exception.NotFoundException;
import com.talentmatch.web.dto.CandidateDetailResponse;
import com.talentmatch.web.dto.CandidateRequest;
import com.talentmatch.web.dto.CandidateSkillRequest;
import com.talentmatch.web.dto.CandidateSkillResponse;
import com.talentmatch.web.dto.CandidateSummaryResponse;
import com.talentmatch.web.dto.PageResponse;
import com.talentmatch.web.error.ErrorCode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Candidate CRUD with ETL-equivalent normalization and skill-link diffing. */
@Service
public class CandidateService {

    public static final int MAX_FULL_NAME = 200;
    public static final int MAX_SUMMARY = 20_000;
    public static final int MAX_SKILLS = 100;
    public static final int MAX_YEARS = 60;

    private static final Comparator<CandidateSkillResponse> SKILL_ORDER = Comparator
            .comparing((CandidateSkillResponse s) -> s.name().toLowerCase(Locale.ROOT))
            .thenComparing(CandidateSkillResponse::skillId);

    private final CandidateRepository candidateRepository;
    private final SkillResolver skillResolver;

    public CandidateService(CandidateRepository candidateRepository, SkillResolver skillResolver) {
        this.candidateRepository = candidateRepository;
        this.skillResolver = skillResolver;
    }

    @Transactional(readOnly = true)
    public PageResponse<CandidateSummaryResponse> list(int page, int size, String skill) {
        String skillName = TextNormalizer.skillName(skill);
        Page<CandidateListRow> rows = skillName == null
                ? candidateRepository.findListPage(Paging.of(page, size))
                : candidateRepository.findListPageBySkill(skillName, Paging.of(page, size));
        return PageResponse.of(rows, r -> new CandidateSummaryResponse(r.id(), r.fullName(), r.email(), r.summary()));
    }

    @Transactional(readOnly = true)
    public CandidateDetailResponse get(UUID id) {
        Candidate c = candidateRepository.findWithSkillsById(id).orElseThrow(() -> NotFoundException.candidate(id));
        return toDetail(c, c.getUpdatedAt());
    }

    /** Id of the candidate with this (normalized) email, if any. */
    @Transactional(readOnly = true)
    public java.util.Optional<UUID> findIdByEmail(String email) {
        String normalized = TextNormalizer.email(email);
        return normalized == null ? java.util.Optional.empty()
                : candidateRepository.findByEmail(normalized).map(Candidate::getId);
    }

    @Transactional
    public CandidateDetailResponse create(CandidateRequest request) {
        Validated v = validate(request);
        ensureEmailFree(v.email(), null);

        Candidate candidate = candidateRepository.save(new Candidate(v.fullName(), v.email(), v.summary()));
        for (DesiredSkill d : v.skills().values()) {
            candidate.getSkills().add(new CandidateSkill(candidate, d.skill(), d.years()));
        }
        candidateRepository.flush();
        return toDetail(candidate, candidate.getUpdatedAt());
    }

    /**
     * Updates a candidate through the candidates API. The owner's own candidate is refused (409):
     * it mirrors the confirmed profile and is changed only through {@code PUT /api/profile}.
     */
    @Transactional
    public CandidateDetailResponse update(UUID id, CandidateRequest request) {
        if (candidateRepository.isOwnerProfileCandidate(id)) {
            throw new ConflictException(ErrorCode.DATA_CONFLICT,
                    "This candidate is your profile; edit it with PUT /api/profile.");
        }
        return updateProfileCandidate(id, request);
    }

    /** Update without the owner guard; only for the profile service, which keeps both in sync. */
    @Transactional
    public CandidateDetailResponse updateProfileCandidate(UUID id, CandidateRequest request) {
        Candidate candidate = candidateRepository.findWithSkillsById(id)
                .orElseThrow(() -> NotFoundException.candidate(id));
        Validated v = validate(request);
        ensureEmailFree(v.email(), id);

        // Scalars: Hibernate dirty checking writes nothing when values are unchanged.
        candidate.setFullName(v.fullName());
        candidate.setEmail(v.email());
        candidate.setSummary(v.summary());

        boolean linksChanged = false;
        Map<UUID, DesiredSkill> desired = new HashMap<>(v.skills());
        Iterator<CandidateSkill> it = candidate.getSkills().iterator();
        while (it.hasNext()) {
            CandidateSkill link = it.next();
            DesiredSkill d = desired.remove(link.getSkill().getId());
            if (d == null) {
                it.remove();                       // orphanRemoval deletes the row
                linksChanged = true;
            } else if (!Objects.equals(link.getYearsExperience(), d.years())) {
                link.setYearsExperience(d.years());
                linksChanged = true;
            }
        }
        for (DesiredSkill d : desired.values()) {
            candidate.getSkills().add(new CandidateSkill(candidate, d.skill(), d.years()));
            linksChanged = true;
        }
        candidateRepository.flush();

        Instant updatedAt = candidate.getUpdatedAt();
        if (linksChanged) {
            // Link changes bump candidate.updated_at in the database (V2 triggers).
            updatedAt = candidateRepository.findUpdatedAt(id).orElse(updatedAt);
        }
        return toDetail(candidate, updatedAt);
    }

    @Transactional
    public void delete(UUID id) {
        if (candidateRepository.isOwnerProfileCandidate(id)) {
            throw new ConflictException(ErrorCode.DATA_CONFLICT,
                    "This candidate is your profile; it can't be deleted here.");
        }
        if (candidateRepository.deleteByIdReturningCount(id) == 0) {
            throw NotFoundException.candidate(id);
        }
    }

    // ------------------------------------------------------------------ helpers

    private record DesiredSkill(Skill skill, Integer years) {
    }

    private record Validated(String fullName, String email, String summary, Map<UUID, DesiredSkill> skills) {
    }

    private Validated validate(CandidateRequest request) {
        if (request == null) {
            request = new CandidateRequest(null, null, null, null);
        }
        String fullName = TextNormalizer.text(request.fullName());
        String email = TextNormalizer.email(request.email());
        String summary = TextNormalizer.text(request.summary());
        List<CandidateSkillRequest> skills = request.skills() == null ? List.of() : request.skills();

        FieldErrors errors = new FieldErrors();
        if (errors.required("fullName", fullName, "Full name is required.")) {
            errors.maxLength("fullName", fullName, MAX_FULL_NAME, "Full name");
        }
        if (errors.required("email", email, "Email is required.")) {
            if (email.length() > TextNormalizer.MAX_EMAIL_LENGTH) {
                errors.maxLength("email", email, TextNormalizer.MAX_EMAIL_LENGTH, "Email");
            } else if (!TextNormalizer.isValidEmail(email)) {
                errors.add("email", "'" + email + "' is not a valid email address. Use a format like ada@example.com.");
            }
        }
        errors.maxLength("summary", summary, MAX_SUMMARY, "Summary");

        Map<UUID, DesiredSkill> desired = new LinkedHashMap<>();
        if (skills.size() > MAX_SKILLS) {
            errors.add("skills", "A candidate can list at most " + MAX_SKILLS + " skills (got " + skills.size() + ").");
        } else {
            List<String> names = new ArrayList<>(skills.size());
            for (int i = 0; i < skills.size(); i++) {
                CandidateSkillRequest s = skills.get(i);
                String prefix = "skills[" + i + "]";
                if (s == null) {
                    errors.add(prefix, "Skill entry must not be null.");
                    names.add(null);
                    continue;
                }
                String name = TextNormalizer.skillName(s.name());
                if (errors.required(prefix + ".name", name, "Skill name is required.")) {
                    errors.maxLength(prefix + ".name", name, SkillResolver.MAX_SKILL_NAME_LENGTH, "Skill name");
                }
                Integer years = s.yearsExperience();
                if (years != null && (years < 0 || years > MAX_YEARS)) {
                    errors.add(prefix + ".yearsExperience",
                            "Years of experience must be between 0 and " + MAX_YEARS + " (got " + years + ").");
                }
                names.add(name);
            }
            Map<Integer, Skill> resolved = skillResolver.resolve(names, "skills", errors);
            for (Map.Entry<Integer, Skill> e : resolved.entrySet()) {
                Skill skill = e.getValue();
                desired.put(skill.getId(), new DesiredSkill(skill, skills.get(e.getKey()).yearsExperience()));
            }
        }
        errors.throwIfAny();
        return new Validated(fullName, email, summary, desired);
    }

    private void ensureEmailFree(String email, UUID selfId) {
        candidateRepository.findByEmail(email).ifPresent(existing -> {
            if (!existing.getId().equals(selfId)) {
                throw new ConflictException(ErrorCode.EMAIL_ALREADY_EXISTS,
                        "A candidate with email " + email + " already exists (id " + existing.getId() + ").");
            }
        });
    }

    private static CandidateDetailResponse toDetail(Candidate c, Instant updatedAt) {
        List<CandidateSkillResponse> skills = c.getSkills().stream()
                .map(cs -> new CandidateSkillResponse(cs.getSkill().getId(), cs.getSkill().getName(),
                        cs.getSkill().getCategory(), cs.getYearsExperience()))
                .sorted(SKILL_ORDER)
                .toList();
        return new CandidateDetailResponse(c.getId(), c.getFullName(), c.getEmail(), c.getSummary(),
                skills, c.getCreatedAt(), updatedAt);
    }
}
