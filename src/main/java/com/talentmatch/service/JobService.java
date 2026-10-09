package com.talentmatch.service;

import com.talentmatch.domain.entity.Job;
import com.talentmatch.domain.entity.JobSkill;
import com.talentmatch.domain.entity.Skill;
import com.talentmatch.repository.JobRepository;
import com.talentmatch.repository.projection.JobListRow;
import com.talentmatch.service.exception.ConflictException;
import com.talentmatch.service.exception.NotFoundException;
import com.talentmatch.web.dto.JobDetailResponse;
import com.talentmatch.web.dto.JobRequest;
import com.talentmatch.web.dto.JobSkillRequest;
import com.talentmatch.web.dto.JobSkillResponse;
import com.talentmatch.web.dto.JobSummaryResponse;
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
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Job CRUD with ETL-equivalent normalization and skill-link diffing. */
@Service
public class JobService {

    public static final int MAX_TITLE = 300;
    public static final int MAX_COMPANY = 200;
    public static final int MAX_DESCRIPTION = 20_000;
    public static final int MAX_SKILLS = 100;

    public static final String NO_SKILLS_MESSAGE = "Add at least one skill so candidates can be ranked for this job.";

    private static final Comparator<JobSkillResponse> SKILL_ORDER = Comparator
            .comparing((JobSkillResponse s) -> s.name().toLowerCase(Locale.ROOT))
            .thenComparing(JobSkillResponse::skillId);

    private final JobRepository jobRepository;
    private final SkillResolver skillResolver;

    public JobService(JobRepository jobRepository, SkillResolver skillResolver) {
        this.jobRepository = jobRepository;
        this.skillResolver = skillResolver;
    }

    @Transactional(readOnly = true)
    public PageResponse<JobSummaryResponse> list(int page, int size, String skill) {
        String skillName = TextNormalizer.skillName(skill);
        Page<JobListRow> rows = skillName == null
                ? jobRepository.findListPage(Paging.of(page, size))
                : jobRepository.findListPageBySkill(skillName, Paging.of(page, size));
        return PageResponse.of(rows, r -> {
            int count = r.skillCount() == null ? 0 : r.skillCount().intValue();
            return new JobSummaryResponse(r.id(), r.title(), r.company(), r.description(), count, count > 0,
                    r.origin());
        });
    }

    @Transactional(readOnly = true)
    public JobDetailResponse get(UUID id) {
        Job job = jobRepository.findWithSkillsById(id).orElseThrow(() -> NotFoundException.job(id));
        return toDetail(job, job.getUpdatedAt());
    }

    @Transactional
    public JobDetailResponse create(JobRequest request) {
        Validated v = validate(request);
        ensureTitleCompanyFree(v.title(), v.company(), null);

        Job job = jobRepository.save(new Job(v.title(), v.company(), v.description()));
        for (DesiredSkill d : v.skills().values()) {
            job.getSkills().add(new JobSkill(job, d.skill(), d.required()));
        }
        jobRepository.flush();
        return toDetail(job, job.getUpdatedAt());
    }

    /** Updates a MANUAL job. FEED jobs are owned by the job feed and refused (409) before validation. */
    @Transactional
    public JobDetailResponse update(UUID id, JobRequest request) {
        Job job = jobRepository.findWithSkillsById(id).orElseThrow(() -> NotFoundException.job(id));
        if (job.isFeed()) {
            throw feedJobReadOnly(id);
        }
        Validated v = validate(request);
        ensureTitleCompanyFree(v.title(), v.company(), id);

        job.setTitle(v.title());
        job.setCompany(v.company());
        job.setDescription(v.description());

        boolean linksChanged = false;
        Map<UUID, DesiredSkill> desired = new HashMap<>(v.skills());
        Iterator<JobSkill> it = job.getSkills().iterator();
        while (it.hasNext()) {
            JobSkill link = it.next();
            DesiredSkill d = desired.remove(link.getSkill().getId());
            if (d == null) {
                it.remove();                       // orphanRemoval deletes the row
                linksChanged = true;
            } else if (link.isRequired() != d.required()) {
                link.setRequired(d.required());
                linksChanged = true;
            }
        }
        for (DesiredSkill d : desired.values()) {
            job.getSkills().add(new JobSkill(job, d.skill(), d.required()));
            linksChanged = true;
        }
        jobRepository.flush();

        Instant updatedAt = job.getUpdatedAt();
        if (linksChanged) {
            // Link changes bump job.updated_at in the database (V2 triggers).
            updatedAt = jobRepository.findUpdatedAt(id).orElse(updatedAt);
        }
        return toDetail(job, updatedAt);
    }

    /** Deletes a MANUAL job; a FEED job → 409 (the job feed owns it), an unknown id → 404. */
    @Transactional
    public void delete(UUID id) {
        if (jobRepository.deleteManualByIdReturningCount(id) == 0) {
            // Nothing deleted: either no such job, or a FEED job (origin never changes, so no race).
            if (jobRepository.findOriginById(id).isPresent()) {
                throw feedJobReadOnly(id);
            }
            throw NotFoundException.job(id);
        }
    }

    // ------------------------------------------------------------------ helpers

    private record DesiredSkill(Skill skill, boolean required) {
    }

    private record Validated(String title, String company, String description, Map<UUID, DesiredSkill> skills) {
    }

    private Validated validate(JobRequest request) {
        if (request == null) {
            request = new JobRequest(null, null, null, null);
        }
        String title = TextNormalizer.text(request.title());
        String company = TextNormalizer.text(request.company());
        String description = TextNormalizer.text(request.description());
        List<JobSkillRequest> skills = request.skills() == null ? List.of() : request.skills();

        FieldErrors errors = new FieldErrors();
        if (errors.required("title", title, "Title is required.")) {
            errors.maxLength("title", title, MAX_TITLE, "Title");
        }
        if (errors.required("company", company, "Company is required.")) {
            errors.maxLength("company", company, MAX_COMPANY, "Company");
        }
        errors.maxLength("description", description, MAX_DESCRIPTION, "Description");

        Map<UUID, DesiredSkill> desired = new LinkedHashMap<>();
        if (skills.isEmpty()) {
            errors.add("skills", NO_SKILLS_MESSAGE);
        } else if (skills.size() > MAX_SKILLS) {
            errors.add("skills", "A job can list at most " + MAX_SKILLS + " skills (got " + skills.size() + ").");
        } else {
            List<String> names = new ArrayList<>(skills.size());
            for (int i = 0; i < skills.size(); i++) {
                JobSkillRequest s = skills.get(i);
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
                names.add(name);
            }
            Map<Integer, Skill> resolved = skillResolver.resolve(names, "skills", errors);
            for (Map.Entry<Integer, Skill> e : resolved.entrySet()) {
                Skill skill = e.getValue();
                Boolean required = skills.get(e.getKey()).required();
                desired.put(skill.getId(), new DesiredSkill(skill, required == null || required));
            }
        }
        errors.throwIfAny();
        return new Validated(title, company, description, desired);
    }

    private ConflictException feedJobReadOnly(UUID id) {
        String source = jobRepository.findFeedSourceKey(id).map(k -> " (source " + k + ")").orElse("");
        return new ConflictException(ErrorCode.DATA_CONFLICT, "This job comes from the job feed" + source
                + " and is kept up to date automatically. It can't be edited or deleted here.");
    }

    /** The natural key applies to MANUAL jobs only; FEED jobs may share a title and company. */
    private void ensureTitleCompanyFree(String title, String company, UUID selfId) {
        jobRepository.findManualByTitleAndCompany(title, company).ifPresent(existing -> {
            if (!existing.getId().equals(selfId)) {
                throw new ConflictException(ErrorCode.JOB_ALREADY_EXISTS,
                        "A job titled '" + title + "' at " + company + " already exists (id "
                                + existing.getId() + ").");
            }
        });
    }

    private static JobDetailResponse toDetail(Job job, Instant updatedAt) {
        List<JobSkillResponse> skills = job.getSkills().stream()
                .map(js -> new JobSkillResponse(js.getSkill().getId(), js.getSkill().getName(),
                        js.getSkill().getCategory(), js.isRequired()))
                .sorted(SKILL_ORDER)
                .toList();
        return new JobDetailResponse(job.getId(), job.getTitle(), job.getCompany(), job.getDescription(),
                !skills.isEmpty(), skills, job.getCreatedAt(), updatedAt, job.getOrigin());
    }
}
