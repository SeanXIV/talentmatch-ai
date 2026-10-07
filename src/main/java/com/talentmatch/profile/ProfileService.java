package com.talentmatch.profile;

import com.talentmatch.domain.entity.Skill;
import com.talentmatch.repository.SkillRepository;
import com.talentmatch.service.CandidateService;
import com.talentmatch.service.FieldErrors;
import com.talentmatch.service.TextNormalizer;
import com.talentmatch.service.exception.ApiException;
import com.talentmatch.service.exception.NotFoundException;
import com.talentmatch.web.error.ErrorCode;
import com.talentmatch.web.error.FieldErrorDto;
import org.springframework.http.HttpStatus;
import com.talentmatch.web.dto.CandidateDetailResponse;
import com.talentmatch.web.dto.CandidateRequest;
import com.talentmatch.web.dto.CandidateSkillRequest;
import com.talentmatch.web.dto.ProfileRequest;
import com.talentmatch.web.dto.ProfileResponse;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The owner's master profile. Saving validates the reviewed document strictly, creates missing
 * skills only when asked, and keeps the owner's candidate row (name, email, summary, skills) in
 * sync, so scoring and matching use the confirmed profile. The V2 triggers mark the owner's
 * cached matches stale whenever the skills change.
 *
 * <p>Saves are serialized by a transaction-scoped advisory lock taken first, so two first saves
 * at once cannot create two owner candidates.
 */
@Service
public class ProfileService {

    private final OwnerProfileRepository profiles;
    private final ResumeRepository resumes;
    private final SkillRepository skills;
    private final CandidateService candidates;
    private final Clock clock;

    /** Punctuation, spaces and trailing version numbers are ignored when comparing skill names. */
    private static final Pattern NOT_SKILL_CHAR = Pattern.compile("[^\\p{L}\\p{N}+#]");
    private static final Pattern TRAILING_VERSION = Pattern.compile("\\d+$");
    /** "Postgres" vs "PostgreSQL", "React" vs "ReactJS": one name extends the other by a few letters. */
    private static final int NEAR_DUPLICATE_EXTRA_CHARS = 3;
    private static final int NEAR_DUPLICATE_MIN_LENGTH = 4;

    public ProfileService(OwnerProfileRepository profiles, ResumeRepository resumes, SkillRepository skills,
                          CandidateService candidates, Clock clock) {
        this.profiles = profiles;
        this.resumes = resumes;
        this.skills = skills;
        this.candidates = candidates;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public ProfileResponse get() {
        OwnerProfileRepository.StoredProfile stored = profiles.find().orElseThrow(NotFoundException::profile);
        CandidateDetailResponse candidate = candidates.get(stored.candidateId());
        return new ProfileResponse(stored.candidateId(), stored.resumeId(), stored.version(), stored.profile(),
                candidate.skills(), stored.confirmedAt(), stored.updatedAt(), List.of());
    }

    @Transactional
    public ProfileResponse save(ProfileRequest request) {
        if (request == null) {
            request = new ProfileRequest(null, null, null);
        }
        profiles.lockForSave(); // first statement: serializes concurrent saves (see class doc)
        FieldErrors errors = new FieldErrors();
        if (request.profile() == null) {
            errors.add("profile", "Send the profile to save (for example the 'draft' from GET /api/profile/resume/{id}).");
            errors.throwIfAny();
        }
        ProfileNormalizer.Result normalized = ProfileNormalizer.normalize(request.profile(), ProfileNormalizer.Mode.STRICT,
                clock);
        ProfileDocument doc = normalized.document();
        for (ProfileNormalizer.Issue issue : normalized.issues()) {
            errors.add("profile." + issue.path(), issue.message());
        }
        errors.required("profile.fullName", doc.fullName(), "Your full name is required.");
        errors.required("profile.email", doc.email(), "Your email address is required.");
        if (request.resumeId() != null && resumes.find(request.resumeId()).isEmpty()) {
            errors.add("resumeId", "No uploaded CV with id " + request.resumeId() + ".");
        }
        List<ProfileWarning> warnings = new ArrayList<>();
        createOrRejectUnknownSkills(doc, Boolean.TRUE.equals(request.createMissingSkills()), errors, warnings);
        errors.throwIfAny();

        CandidateRequest candidateRequest = new CandidateRequest(doc.fullName(), doc.email(), candidateSummary(doc),
                doc.skills().stream().map(s -> new CandidateSkillRequest(s.name(), s.years())).toList());
        Optional<UUID> existing = profiles.findCandidateId();
        CandidateDetailResponse candidate = existing.isPresent()
                ? candidates.updateProfileCandidate(existing.get(), candidateRequest)
                : createOwnerCandidate(candidateRequest);

        profiles.save(candidate.id(), request.resumeId(), ProfileJson.write(doc));
        OwnerProfileRepository.StoredProfile stored = profiles.find().orElseThrow();
        return new ProfileResponse(candidate.id(), stored.resumeId(), stored.version(), doc, candidate.skills(),
                stored.confirmedAt(), stored.updatedAt(), List.copyOf(warnings));
    }

    /**
     * First save: creates the owner's candidate. An existing candidate that already uses the
     * email is never adopted or merged (owner decision): the save is refused with 409 on
     * {@code profile.email}, and the owner deletes that candidate or changes its email.
     */
    private CandidateDetailResponse createOwnerCandidate(CandidateRequest request) {
        Optional<UUID> clash = candidates.findIdByEmail(request.email());
        if (clash.isPresent()) {
            String message = "Candidate " + clash.get() + " already uses this email. Delete it or change its email, "
                    + "then save your profile again.";
            throw new ApiException(ErrorCode.EMAIL_ALREADY_EXISTS, HttpStatus.CONFLICT, message,
                    List.of(new FieldErrorDto("profile.email", message)));
        }
        return candidates.create(request);
    }

    /**
     * Unknown skills become field errors, or are created when {@code create} is true. A created
     * skill that looks like an existing one ("Postgres" when "PostgreSQL" exists) adds a warning,
     * because near-duplicates split matching between two skill rows.
     */
    private void createOrRejectUnknownSkills(ProfileDocument doc, boolean create, FieldErrors errors,
                                             List<ProfileWarning> warnings) {
        Set<String> keys = new HashSet<>();
        doc.skills().forEach(s -> keys.add(TextNormalizer.skillKey(s.name())));
        if (keys.isEmpty()) {
            return;
        }
        Set<String> known = new HashSet<>();
        skills.findAllByLowerNameIn(keys).forEach(s -> known.add(TextNormalizer.skillKey(s.getName())));
        List<Skill> toCreate = new ArrayList<>();
        List<Integer> createdAt = new ArrayList<>();
        Set<String> queued = new HashSet<>();
        for (int i = 0; i < doc.skills().size(); i++) {
            String name = doc.skills().get(i).name();
            String key = TextNormalizer.skillKey(name);
            if (known.contains(key) || !queued.add(key)) {
                continue;
            }
            if (create) {
                toCreate.add(new Skill(name, null));
                createdAt.add(i);
            } else {
                errors.add("profile.skills[" + i + "].name", "Unknown skill '" + name + "'. Set createMissingSkills "
                        + "to true to add it, or remove it from the profile.");
            }
        }
        if (create && errors.isEmpty() && !toCreate.isEmpty()) {
            warnNearDuplicates(toCreate, createdAt, warnings);
            skills.saveAll(toCreate);
            skills.flush();
        }
    }

    private void warnNearDuplicates(List<Skill> created, List<Integer> indices, List<ProfileWarning> warnings) {
        List<String> existing = skills.findAll().stream().map(Skill::getName).toList();
        for (int k = 0; k < created.size(); k++) {
            String name = created.get(k).getName();
            for (String other : existing) {
                if (nearDuplicate(name, other)) {
                    warnings.add(new ProfileWarning("profile.skills[" + indices.get(k) + "].name", name,
                            "New skill '" + name + "' was created, but '" + other + "' already exists. If they are "
                                    + "the same, rename it to '" + other + "' and save again so matching uses one skill."));
                    break;
                }
            }
        }
    }

    /** Same letters ignoring case, punctuation and a trailing version, or one extends the other slightly. */
    static boolean nearDuplicate(String a, String b) {
        String x = looseKey(a);
        String y = looseKey(b);
        if (x.isEmpty() || y.isEmpty()) {
            return false;
        }
        if (x.equals(y)) {
            return true;
        }
        String shorter = x.length() <= y.length() ? x : y;
        String longer = shorter == x ? y : x;
        return shorter.length() >= NEAR_DUPLICATE_MIN_LENGTH && longer.startsWith(shorter)
                && longer.length() - shorter.length() <= NEAR_DUPLICATE_EXTRA_CHARS;
    }

    private static String looseKey(String name) {
        String k = NOT_SKILL_CHAR.matcher(name.toLowerCase(Locale.ROOT)).replaceAll("");
        return TRAILING_VERSION.matcher(k).replaceAll("");
    }

    /** The candidate summary used for matching and AI explanations: headline plus summary. */
    static String candidateSummary(ProfileDocument doc) {
        String headline = doc.headline();
        String summary = doc.summary();
        if (headline == null) {
            return summary;
        }
        return summary == null ? headline : headline + "\n\n" + summary;
    }
}
