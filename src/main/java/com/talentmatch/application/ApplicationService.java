package com.talentmatch.application;

import com.talentmatch.application.ApplicationRepository.ApplicationRow;
import com.talentmatch.application.ApplicationRepository.JobSnapshot;
import com.talentmatch.application.DocumentRepository.DocumentRef;
import com.talentmatch.application.content.DocumentKind;
import com.talentmatch.application.tailoring.ApplicationProperties;
import com.talentmatch.profile.OwnerProfileRepository;
import com.talentmatch.service.FieldErrors;
import com.talentmatch.service.Paging;
import com.talentmatch.service.exception.ConflictException;
import com.talentmatch.service.exception.NotFoundException;
import com.talentmatch.web.error.ErrorCode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The application tracker (Phase 7 §4.1): create, list, read, update and delete.
 * <ul>
 *   <li><b>Create</b> tracks one job (at most one application per job, decision n), starting as NEW or
 *       SHORTLISTED, with a snapshot of the job's title, company and URL. With {@code generate=true} it
 *       also queues a generation ({@link GenerationService}).</li>
 *   <li><b>Update</b> runs under the application row lock (10 s lock wait, then 503 SERVICE_BUSY).
 *       Status moves follow {@link ApplicationStatus}'s table unless {@code force=true}. Entering an
 *       applied status sets {@code appliedOn} (default: today in {@code talentmatch.applications.zone})
 *       and the sent versions (default: the newest approved version of each kind, or none); leaving
 *       them (only with {@code force}) clears all three.</li>
 *   <li><b>Delete</b> is a hard delete; documents, files, generations and history cascade.</li>
 * </ul>
 * Logs carry ids and statuses only, never notes.
 */
@Service
public class ApplicationService {

    private static final Logger log = LoggerFactory.getLogger(ApplicationService.class);

    static final int MAX_NOTES_LENGTH = 10_000;
    static final LocalDate EARLIEST_APPLIED_ON = LocalDate.of(2000, 1, 1);

    static final String JOB_ID_REQUIRED = "jobId is required.";
    static final String CREATE_STATUS = "A new application starts as NEW or SHORTLISTED.";
    static final String UPDATE_STATUS = "status must be one of " + ApplicationStatus.NAMES + ".";
    static final String APPLIED_ON_INVALID =
            "appliedOn must be a date like 2026-10-19, not in the future and not before 2000-01-01.";
    static final String APPLIED_ON_TOO_EARLY = "appliedOn can only be set once the application is APPLIED or later.";
    static final String NOTES_TOO_LONG = "Notes can be at most " + MAX_NOTES_LENGTH + " characters.";

    private final ApplicationRepository repository;
    private final DocumentRepository documents;
    private final GenerationService generations;
    private final OwnerProfileRepository profiles;
    private final ApplicationProperties properties;
    private final Clock clock;
    private final TransactionTemplate tx;

    public ApplicationService(ApplicationRepository repository, DocumentRepository documents,
                              GenerationService generations, OwnerProfileRepository profiles,
                              ApplicationProperties properties, Clock clock,
                              PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.documents = documents;
        this.generations = generations;
        this.profiles = profiles;
        this.properties = properties;
        this.clock = clock;
        this.tx = new TransactionTemplate(transactionManager);
    }

    /**
     * A job to track. {@code status} null means NEW. With {@code generate=true} a generation of
     * {@code kinds} (null: both) is queued in the same transaction.
     */
    public record NewApplication(UUID jobId, String status, String notes, Boolean generate, List<String> kinds,
                                 Boolean mentionNoticePeriod) {

        /** Tracking only, no generation. */
        public NewApplication(UUID jobId, String status, String notes) {
            this(jobId, status, notes, null, null, null);
        }
    }

    /**
     * The tracker fields (full replace). {@code status} is required; {@code notes} null clears them. A
     * null {@code appliedOn} or sent id takes the default when entering an applied status, and keeps
     * the stored value while staying in one. {@code force} null means false.
     */
    public record Changes(String status, String appliedOn, String notes, UUID sentCvId, UUID sentCoverLetterId,
                          Boolean force) {
    }

    // ------------------------------------------------------------------ read

    @Transactional(readOnly = true)
    public Page<ApplicationSummaryView> list(Set<ApplicationStatus> statuses, ApplicationSort order, int page,
                                             int size) {
        Pageable pageable = Paging.of(page, size);
        Set<ApplicationStatus> filter = statuses == null ? Set.of() : statuses;
        long total = repository.count(filter);
        if (total == 0) {
            return new PageImpl<>(List.of(), pageable, total);
        }
        List<ApplicationSummaryView> content = repository.findPage(filter, order == null ? ApplicationSort.UPDATED : order,
                pageable.getOffset(), pageable.getPageSize());
        return new PageImpl<>(content, pageable, total);
    }

    @Transactional(readOnly = true)
    public ApplicationView get(UUID id) {
        return view(repository.find(id).orElseThrow(() -> NotFoundException.application(id)));
    }

    // ------------------------------------------------------------------ create

    /**
     * Tracks a job, and with {@code generate=true} queues a generation of its documents (§1.2): the
     * profile check comes first, so nothing is created without one; the generation is queued after commit.
     *
     * @throws com.talentmatch.service.exception.RequestValidationException 400 for invalid fields
     * @throws NotFoundException  404 JOB_NOT_FOUND, or PROFILE_NOT_FOUND with {@code generate}
     * @throws ConflictException  409 APPLICATION_ALREADY_EXISTS naming the existing application
     */
    public ApplicationView create(NewApplication request) {
        NewApplication in = request == null ? new NewApplication(null, null, null) : request;
        FieldErrors errors = new FieldErrors();
        if (in.jobId() == null) {
            errors.add("jobId", JOB_ID_REQUIRED);
        }
        ApplicationStatus status = ApplicationStatus.NEW;
        if (in.status() != null) {
            status = ApplicationStatus.parse(in.status());
            if (status == null || !status.initial()) {
                errors.add("status", CREATE_STATUS);
            }
        }
        String notes = notes(in.notes(), errors);
        GenerationService.Kinds kinds = GenerationService.kinds(in.kinds(), errors);
        errors.throwIfAny();

        UUID jobId = in.jobId();
        ApplicationStatus initial = status;
        boolean generate = Boolean.TRUE.equals(in.generate());
        boolean mention = Boolean.TRUE.equals(in.mentionNoticePeriod());
        ApplicationView created;
        try {
            created = tx.execute(s -> {
                if (generate && profiles.findRef().isEmpty()) {
                    throw NotFoundException.profile();
                }
                JobSnapshot job = repository.findJobForSnapshot(jobId).orElseThrow(() -> NotFoundException.job(jobId));
                repository.findIdByJob(jobId).ifPresent(existing -> {
                    throw alreadyExists(existing);
                });
                UUID id = repository.insert(job, initial, notes);
                repository.insertEvent(id, null, initial, false);
                if (generate) {
                    // The new row isn't visible to anyone else yet, so no lock is needed for the insert.
                    generations.insertLocked(id, kinds, mention);
                }
                return view(repository.find(id).orElseThrow(() -> NotFoundException.application(id)));
            });
        } catch (DuplicateKeyException e) {
            // Tracked concurrently, after the check above (uq_application_job): report the winner.
            // Read in a new transaction; the failed one is gone.
            throw repository.findIdByJob(jobId).map(ApplicationService::alreadyExists)
                    .orElseGet(() -> new ConflictException(ErrorCode.DATA_CONFLICT,
                            "The job changed while it was being tracked. Reload and try again."));
        }
        log.info("Application created id={} job={} status={}", created.id(), jobId, initial);
        if (generate && created.latestGeneration() != null) {
            log.info("Generation queued application={} generation={} cv={} coverLetter={}", created.id(),
                    created.latestGeneration().id(), kinds.cv(), kinds.coverLetter());
            generations.enqueue(created.latestGeneration().id());   // after commit, so the worker sees the row
        }
        return created;
    }

    // ------------------------------------------------------------------ update

    /**
     * Replaces the tracker fields (§4.1 update, steps 1–7).
     *
     * @throws com.talentmatch.service.exception.RequestValidationException 400 for invalid fields or sent ids
     * @throws NotFoundException  404 APPLICATION_NOT_FOUND
     * @throws ConflictException  409 INVALID_STATUS_TRANSITION
     */
    @Transactional
    public ApplicationView update(UUID id, Changes request) {
        ApplicationRow current = repository.lockForUpdate(id).orElseThrow(() -> NotFoundException.application(id));
        Changes in = request == null ? new Changes(null, null, null, null, null, null) : request;
        boolean force = Boolean.TRUE.equals(in.force());

        // 2. fields
        FieldErrors errors = new FieldErrors();
        ApplicationStatus target = ApplicationStatus.parse(in.status());
        if (target == null) {
            errors.add("status", UPDATE_STATUS);
        }
        LocalDate appliedOn = null;
        if (in.appliedOn() != null) {
            if (target != null && !target.applied()) {
                errors.add("appliedOn", APPLIED_ON_TOO_EARLY);
            } else {
                appliedOn = appliedOn(in.appliedOn());
                if (appliedOn == null) {
                    errors.add("appliedOn", APPLIED_ON_INVALID);
                }
            }
        }
        if (target != null && !target.applied()) {
            if (in.sentCvId() != null) {
                errors.add("sentCvId", sentTooEarly("sentCvId"));
            }
            if (in.sentCoverLetterId() != null) {
                errors.add("sentCoverLetterId", sentTooEarly("sentCoverLetterId"));
            }
        }
        String notes = notes(in.notes(), errors);
        errors.throwIfAny();

        // 3. transition
        ApplicationStatus from = current.status();
        boolean statusChanged = target != from;
        if (statusChanged && !force && !from.canMoveTo(target)) {
            throw invalidTransition(from, target);
        }

        // 4–6. applied date and sent versions
        UUID sentCv = null;
        UUID sentCoverLetter = null;
        if (target.applied()) {
            boolean entering = !from.applied();
            if (appliedOn == null) {
                appliedOn = entering ? today() : current.appliedOn();
            }
            FieldErrors sentErrors = new FieldErrors();
            sentCv = sent(sentErrors, "sentCvId", id, DocumentKind.CV, in.sentCvId(), entering, current.sentCvId());
            sentCoverLetter = sent(sentErrors, "sentCoverLetterId", id, DocumentKind.COVER_LETTER,
                    in.sentCoverLetterId(), entering, current.sentCoverLetterId());
            sentErrors.throwIfAny();
        }
        // Not applied: the date and sent versions stay (or become) null; leaving the applied statuses
        // is only possible with force.

        // 7. write
        repository.update(id, target, statusChanged, target.applied() ? appliedOn : null, notes, sentCv,
                sentCoverLetter);
        if (statusChanged) {
            repository.insertEvent(id, from, target, force);
            log.info("Application status changed id={} from={} to={} forced={}", id, from, target, force);
        }
        return view(repository.find(id).orElseThrow(() -> NotFoundException.application(id)));
    }

    // ------------------------------------------------------------------ delete

    /** Deletes the application and everything under it (any status). */
    @Transactional
    public void delete(UUID id) {
        ApplicationRow existing = repository.lockForUpdate(id).orElseThrow(() -> NotFoundException.application(id));
        repository.delete(id);
        log.info("Application deleted id={} status={}", id, existing.status());
    }

    // ------------------------------------------------------------------ helpers

    private ApplicationView view(ApplicationRow row) {
        Integer currentProfile = profiles.findRef().map(OwnerProfileRepository.OwnerRef::version).orElse(null);
        List<DocumentSummaryView> versions = documents.summaries(row.id(), currentProfile);
        return new ApplicationView(
                row.id(),
                row.jobId(),
                row.jobTitle(),
                row.jobCompany(),
                row.jobUrl(),
                row.jobId() != null,
                row.jobClosed(),
                row.jobOrigin(),
                row.status(),
                row.statusChangedAt(),
                row.appliedOn(),
                row.notes(),
                row.status().allowedTransitions(),
                sentVersion(versions, row.sentCvId()),
                sentVersion(versions, row.sentCoverLetterId()),
                new ApplicationView.Documents(kindDocuments(versions, DocumentKind.CV),
                        kindDocuments(versions, DocumentKind.COVER_LETTER)),
                generations.latest(row.id()),
                repository.events(row.id()),
                row.createdAt(),
                row.updatedAt());
    }

    private static ApplicationView.KindDocuments kindDocuments(List<DocumentSummaryView> versions, DocumentKind kind) {
        List<DocumentSummaryView> ofKind = versions.stream().filter(v -> v.kind() == kind).toList();
        DocumentSummaryView latest = ofKind.isEmpty() ? null : ofKind.get(0);
        DocumentSummaryView approved = ofKind.stream().filter(v -> v.approvedAt() != null).findFirst().orElse(null);
        return new ApplicationView.KindDocuments(latest, approved, ofKind.size());
    }

    private static ApplicationView.SentVersion sentVersion(List<DocumentSummaryView> versions, UUID id) {
        if (id == null) {
            return null;
        }
        return versions.stream().filter(v -> v.id().equals(id)).findFirst()
                .map(v -> new ApplicationView.SentVersion(v.id(), v.version())).orElse(null);
    }

    /**
     * The sent version of one kind for an applied status (decision o): an explicit id must be an
     * approved version of that kind in this application; none given takes the newest approved version
     * when entering the applied statuses, and keeps the stored one otherwise.
     */
    private UUID sent(FieldErrors errors, String field, UUID applicationId, DocumentKind kind, UUID requested,
                      boolean entering, UUID stored) {
        if (requested == null) {
            return entering
                    ? documents.newestApproved(applicationId, kind).map(DocumentRef::id).orElse(null)
                    : stored;
        }
        Optional<DocumentRef> ref = documents.findRef(applicationId, requested).filter(r -> r.kind() == kind);
        if (ref.isEmpty()) {
            errors.add(field, "No " + kind.label() + " version " + requested + " in this application.");
            return null;
        }
        if (!ref.get().approved()) {
            errors.add(field, capitalise(kind.label()) + " version " + ref.get().version()
                    + " is not approved yet. Approve it first (POST /api/applications/" + applicationId
                    + "/documents/" + requested + "/approve) or choose an approved version.");
            return null;
        }
        return requested;
    }

    /** Strict ISO date (yyyy-MM-dd) from 2000-01-01 to today in the configured zone; null when invalid. */
    private LocalDate appliedOn(String raw) {
        LocalDate date;
        try {
            date = LocalDate.parse(raw.strip());
        } catch (DateTimeParseException e) {
            return null;
        }
        return date.isBefore(EARLIEST_APPLIED_ON) || date.isAfter(today()) ? null : date;
    }

    /** Today in {@code talentmatch.applications.zone}, from the application Clock's instant. */
    private LocalDate today() {
        return LocalDate.ofInstant(clock.instant(), properties.zoneId());
    }

    /**
     * Notes as stored: NUL characters removed (PostgreSQL text can't hold them), trimmed, blank = none.
     * Length is counted in characters (code points), as V7's {@code char_length} CHECK does.
     */
    static String notes(String raw, FieldErrors errors) {
        if (raw == null) {
            return null;
        }
        String s = raw.replace("\u0000", "").strip();
        if (s.isEmpty()) {
            return null;
        }
        if (s.codePointCount(0, s.length()) > MAX_NOTES_LENGTH) {
            errors.add("notes", NOTES_TOO_LONG);
        }
        return s;
    }

    private static String sentTooEarly(String field) {
        return field + " can only be set once the application is APPLIED or later.";
    }

    private static ConflictException invalidTransition(ApplicationStatus from, ApplicationStatus to) {
        List<ApplicationStatus> allowed = from.allowedTransitions();
        String next = allowed.isEmpty() ? "none"
                : allowed.stream().map(Enum::name).collect(Collectors.joining(", "));
        return new ConflictException(ErrorCode.INVALID_STATUS_TRANSITION, "An application can't move from " + from
                + " to " + to + ". Allowed next: " + next + ". To correct a mistake, send force=true.");
    }

    private static ConflictException alreadyExists(UUID existing) {
        return new ConflictException(ErrorCode.APPLICATION_ALREADY_EXISTS, "This job is already tracked as application "
                + existing + ". Open it with GET /api/applications/" + existing + ".");
    }

    private static String capitalise(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
