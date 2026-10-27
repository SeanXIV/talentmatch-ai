package com.talentmatch.application;

import com.talentmatch.ai.Interrupts;
import com.talentmatch.ai.LocalModelGate;
import com.talentmatch.application.ApplicationRepository.ApplicationRow;
import com.talentmatch.application.DocumentRepository.DocumentRef;
import com.talentmatch.application.DocumentRepository.NewVersion;
import com.talentmatch.application.GenerationRepository.ActiveGeneration;
import com.talentmatch.application.GenerationRepository.GenerationRow;
import com.talentmatch.application.GenerationRepository.JobRow;
import com.talentmatch.application.content.DocumentKind;
import com.talentmatch.application.content.Finding;
import com.talentmatch.application.content.Severity;
import com.talentmatch.application.tailoring.ApplicationProperties;
import com.talentmatch.application.tailoring.FactCheck;
import com.talentmatch.application.tailoring.GenerationFailure;
import com.talentmatch.application.tailoring.ProfileFacts;
import com.talentmatch.application.tailoring.TailoringEngine;
import com.talentmatch.application.tailoring.TailoringModel;
import com.talentmatch.application.tailoring.TailoringOutcome;
import com.talentmatch.feed.skills.SkillDictionary;
import com.talentmatch.preferences.PreferencesRepository;
import com.talentmatch.profile.OwnerProfileRepository;
import com.talentmatch.profile.OwnerProfileRepository.StoredProfile;
import com.talentmatch.service.FieldErrors;
import com.talentmatch.service.exception.ConflictException;
import com.talentmatch.service.exception.NotFoundException;
import com.talentmatch.web.error.ErrorCode;
import dev.langchain4j.model.output.TokenUsage;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.Timer;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Collections;
import java.util.EnumSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * AI generation of an application's tailored CV and cover letter (Phase 7 §4.6, decision d): the Phase 4
 * pattern of a persisted queue on the single-threaded {@code applicationExecutor}.
 * <ul>
 *   <li><b>Request</b> ({@link #request}, and {@code generate} on create): under the application row lock,
 *       insert a PENDING row (at most one active per application), then queue it after commit.</li>
 *   <li><b>Run</b> ({@link #run}, never throws): claim PENDING → RUNNING (attempts + 1; out of attempts →
 *       FAILED/TOO_MANY_ATTEMPTS); check the model (AI off → AI_DISABLED; a hosted provider without
 *       {@code allow-remote-generation} → REMOTE_GENERATION_DISABLED, no call made); read the job, the owner
 *       profile and, when asked, the notice period (job gone → JOB_DELETED); then one
 *       {@link TailoringEngine} call per wanted kind, CV first, while holding {@link LocalModelGate}
 *       exclusively (local model only). Each kind is stored right after its call, so the CV is readable
 *       while the letter is generated. SUCCEEDED when at least one document is stored; otherwise FAILED with
 *       the CV's failure (the letter's when only the letter was wanted).</li>
 *   <li><b>Interrupts</b> (shutdown) leave the row RUNNING; {@link GenerationRecovery} re-queues it, and the
 *       re-run skips a kind already stored by the interrupted attempt.</li>
 *   <li><b>Deleted application</b> mid-run: storing finds no row and discards the result (INFO).</li>
 * </ul>
 * The circuit breaker is not fed (as with CV extraction and enrichment).
 *
 * <p>Logs one INFO line per kind with ids, outcome, latency, token and finding counts. Never logs profile,
 * job or generated text, findings' values or prompts: exceptions are logged by class name (plus SQLState).
 */
@Service
public class GenerationService {

    private static final Logger log = LoggerFactory.getLogger(GenerationService.class);

    static final String TIMER = "talentmatch.ai.tailoring";
    static final String KINDS_INVALID = "kinds must contain CV and/or COVER_LETTER, each at most once.";
    static final String JOB_GONE = "The job for this application no longer exists, so documents can't be tailored "
            + "to it. Existing versions are kept.";
    private static final int MAX_CAUSE_DEPTH = 16;

    private final GenerationRepository repository;
    private final ApplicationRepository applications;
    private final DocumentRepository documents;
    private final OwnerProfileRepository profiles;
    private final PreferencesRepository preferences;
    private final SkillDictionary dictionary;
    private final TailoringEngine engine;
    private final ObjectProvider<TailoringModel> model;
    private final ObjectProvider<LocalModelGate> gate;
    private final Executor executor;
    private final ApplicationProperties properties;
    private final Clock clock;
    private final TransactionTemplate tx;
    private final TransactionTemplate readTx;
    private final MeterRegistry meterRegistry;

    public GenerationService(GenerationRepository repository, ApplicationRepository applications,
                             DocumentRepository documents, OwnerProfileRepository profiles,
                             PreferencesRepository preferences, SkillDictionary dictionary, TailoringEngine engine,
                             ObjectProvider<TailoringModel> model, ObjectProvider<LocalModelGate> gate,
                             @Qualifier(ApplicationConfig.APPLICATION_EXECUTOR) Executor executor,
                             ApplicationProperties properties, Clock clock,
                             PlatformTransactionManager transactionManager,
                             ObjectProvider<MeterRegistry> meterRegistry) {
        this.repository = repository;
        this.applications = applications;
        this.documents = documents;
        this.profiles = profiles;
        this.preferences = preferences;
        this.dictionary = dictionary;
        this.engine = engine;
        this.model = model;
        this.gate = gate;
        this.executor = executor;
        this.properties = properties;
        this.clock = clock;
        this.tx = new TransactionTemplate(transactionManager);
        this.readTx = new TransactionTemplate(transactionManager);
        this.readTx.setReadOnly(true);
        MeterRegistry registry = meterRegistry.getIfAvailable();
        this.meterRegistry = registry == null ? Metrics.globalRegistry : registry;
    }

    /**
     * The document kinds a generation produces.
     *
     * @param cv          tailor the CV
     * @param coverLetter write the cover letter
     */
    public record Kinds(boolean cv, boolean coverLetter) {

        public static final Kinds BOTH = new Kinds(true, true);
    }

    /**
     * Parses a {@code kinds} field: null means both; otherwise CV and/or COVER_LETTER (case-insensitive),
     * each at most once. Adds the §5.3 field error and returns {@link Kinds#BOTH} when invalid.
     */
    public static Kinds kinds(List<String> raw, FieldErrors errors) {
        if (raw == null) {
            return Kinds.BOTH;
        }
        Set<DocumentKind> seen = EnumSet.noneOf(DocumentKind.class);
        boolean valid = !raw.isEmpty();
        for (String value : raw) {
            DocumentKind kind = parseKind(value);
            if (kind == null || !seen.add(kind)) {
                valid = false;
                break;
            }
        }
        if (!valid) {
            errors.add("kinds", KINDS_INVALID);
            return Kinds.BOTH;
        }
        return new Kinds(seen.contains(DocumentKind.CV), seen.contains(DocumentKind.COVER_LETTER));
    }

    private static DocumentKind parseKind(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return DocumentKind.valueOf(value.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ request

    /**
     * Queues a generation for an application (§5.4).
     *
     * @throws com.talentmatch.service.exception.RequestValidationException 400 for invalid {@code kinds}
     * @throws NotFoundException  404 APPLICATION_NOT_FOUND or PROFILE_NOT_FOUND
     * @throws ConflictException  409 DATA_CONFLICT (job deleted) or GENERATION_IN_PROGRESS
     */
    public GenerationView request(UUID applicationId, List<String> rawKinds, Boolean mentionNoticePeriod) {
        FieldErrors errors = new FieldErrors();
        Kinds kinds = kinds(rawKinds, errors);
        errors.throwIfAny();
        boolean mention = Boolean.TRUE.equals(mentionNoticePeriod);

        GenerationView queued;
        try {
            queued = tx.execute(s -> {
                ApplicationRow row = applications.lockForUpdate(applicationId)
                        .orElseThrow(() -> NotFoundException.application(applicationId));
                if (row.jobId() == null || row.jobOrigin() == null) {
                    throw new ConflictException(ErrorCode.DATA_CONFLICT, JOB_GONE);
                }
                if (profiles.findRef().isEmpty()) {
                    throw NotFoundException.profile();
                }
                UUID id = insertLocked(applicationId, kinds, mention);
                return repository.find(id).orElseThrow().view();
            });
        } catch (DuplicateKeyException e) {
            // Backstop only: the row lock above serializes requests, so the check in insertLocked saw it first.
            throw repository.findActive(applicationId).map(a -> inProgress(applicationId, a))
                    .orElseGet(() -> new ConflictException(ErrorCode.DATA_CONFLICT,
                            "The application changed while the generation was being queued. Try again."));
        }
        log.info("Generation queued application={} generation={} cv={} coverLetter={}", applicationId, queued.id(),
                kinds.cv(), kinds.coverLetter());
        enqueue(queued.id());                       // after commit, so the worker sees the row
        return queued;
    }

    /**
     * Inserts a PENDING generation. Call inside a transaction holding the application row lock; the caller
     * queues it with {@link #enqueue} after commit.
     *
     * @throws ConflictException 409 GENERATION_IN_PROGRESS when one is already PENDING or RUNNING
     */
    UUID insertLocked(UUID applicationId, Kinds kinds, boolean mentionNoticePeriod) {
        Optional<ActiveGeneration> active = repository.findActive(applicationId);
        if (active.isPresent()) {
            throw inProgress(applicationId, active.get());
        }
        return repository.insert(applicationId, kinds.cv(), kinds.coverLetter(), mentionNoticePeriod);
    }

    /** One generation of the application. @throws NotFoundException 404 GENERATION_NOT_FOUND */
    public GenerationView get(UUID applicationId, UUID generationId) {
        return repository.find(applicationId, generationId).map(GenerationRow::view)
                .orElseThrow(() -> NotFoundException.generation(applicationId, generationId));
    }

    /** The application's newest generation, or null. */
    GenerationView latest(UUID applicationId) {
        return repository.latestFor(applicationId).map(GenerationRow::view).orElse(null);
    }

    private static ConflictException inProgress(UUID applicationId, ActiveGeneration active) {
        return new ConflictException(ErrorCode.GENERATION_IN_PROGRESS, "A generation for this application is already "
                + active.status() + " (generation " + active.id() + "). Wait for it with GET /api/applications/"
                + applicationId + "/generations/" + active.id() + ".");
    }

    // ------------------------------------------------------------------ queue

    /** Queues the generation; a full queue marks it FAILED/QUEUE_FULL. */
    public void enqueue(UUID generationId) {
        try {
            executor.execute(() -> run(generationId));
        } catch (RejectedExecutionException e) { // includes Spring's TaskRejectedException
            log.warn("Generation queue is full; generation {} not queued", generationId);
            safeFail(generationId, GenerationFailure.QUEUE_FULL, null);
        }
    }

    /** Runs one generation on the calling thread. Never throws. */
    void run(UUID generationId) {
        try {
            if (!repository.claim(generationId, clock.instant())) {
                if (repository.failExhausted(generationId, clock.instant())) {
                    log.warn("Generation generation={} outcome=too_many_attempts (interrupted {} times)", generationId,
                            GenerationRepository.MAX_ATTEMPTS);
                }
                return; // already taken, finished, deleted, or out of attempts
            }
            Optional<GenerationRow> claimed = repository.find(generationId);
            if (claimed.isEmpty()) {
                return; // application deleted right after the claim
            }
            GenerationRow g = claimed.get();
            TailoringModel m = model.getIfAvailable();
            if (m == null) {
                repository.fail(generationId, GenerationFailure.AI_DISABLED, null, clock.instant());
                log.info("Generation application={} generation={} outcome=ai_disabled", g.applicationId(),
                        generationId);
                return;
            }
            if (!m.local() && !properties.allowRemoteGeneration()) {
                // the profile would leave the machine: only with the owner's explicit opt-in (decision c)
                repository.fail(generationId, GenerationFailure.REMOTE_GENERATION_DISABLED, m.label(),
                        clock.instant());
                log.info("Generation application={} generation={} model={} outcome=remote_generation_disabled",
                        g.applicationId(), generationId, m.label());
                return;
            }
            generate(g, m);
        } catch (RuntimeException e) {
            if (Interrupts.interrupted(e)) {
                logInterrupted(generationId);
                return;
            }
            log.error("Unexpected error in generation {} ({}); marking it failed", generationId, describe(e));
            safeFail(generationId, GenerationFailure.PROVIDER_ERROR, null);
        }
    }

    /** What one run reads before calling the model. */
    private record Inputs(JobRow job, StoredProfile profile, Integer noticePeriodDays) {
    }

    private void generate(GenerationRow g, TailoringModel m) {
        UUID generationId = g.id();
        UUID applicationId = g.applicationId();
        Inputs in = readTx.execute(s -> read(g));
        if (in == null) {
            log.info("Generation application={} generation={} outcome=discarded (application deleted)",
                    applicationId, generationId);
            return;
        }
        if (in.job() == null) {
            repository.fail(generationId, GenerationFailure.JOB_DELETED, m.label(), clock.instant());
            log.info("Generation application={} generation={} model={} outcome=job_deleted", applicationId,
                    generationId, m.label());
            return;
        }
        if (in.profile() == null) {
            // Requests check for a profile and owner_profile is never deleted, so this can't normally happen.
            throw new IllegalStateException("No owner profile to tailor from");
        }

        FactCheck check = FactCheck.load(dictionary::refresh);
        LocalDate today = LocalDate.ofInstant(clock.instant(), properties.zoneId());
        int profileVersion = in.profile().version();
        ProfileFacts facts = ProfileFacts.of(in.profile().profile(), properties.generation().maxProfileChars(), check,
                today);
        if (!repository.start(generationId, m.label(), profileVersion)) {
            return; // deleted or failed elsewhere meanwhile
        }
        TailoringEngine.Options options = new TailoringEngine.Options(today, g.mentionNoticePeriod(),
                in.noticePeriodDays());

        LocalModelGate localGate = m.local() ? gate.getIfAvailable() : null;
        if (localGate != null) {
            try {
                localGate.acquireExclusive();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                logInterrupted(generationId);
                return;
            }
        }
        boolean stored = false;
        GenerationFailure firstFailure = null;
        try {
            for (DocumentKind kind : DocumentKind.values()) {
                if (!g.wants(kind)) {
                    continue;
                }
                if (g.documentId(kind) != null) {
                    stored = true;                  // stored by an interrupted earlier attempt: keep it
                    continue;
                }
                KindResult result = generateKind(g, m, kind, in, facts, check, options, profileVersion);
                if (result.discarded()) {
                    log.info("Generation application={} generation={} outcome=discarded (application deleted)",
                            applicationId, generationId);
                    return;
                }
                if (result.stored()) {
                    stored = true;
                } else if (firstFailure == null) {
                    firstFailure = result.failure();
                }
            }
        } finally {
            if (localGate != null) {
                localGate.releaseExclusive();
            }
        }

        boolean finished = stored
                ? repository.succeed(generationId, m.label(), clock.instant())
                : repository.fail(generationId, firstFailure == null ? GenerationFailure.INVALID_OUTPUT : firstFailure,
                        m.label(), clock.instant());
        if (!finished) {
            log.info("Generation application={} generation={} outcome=discarded (no longer running)", applicationId,
                    generationId);
        }
    }

    /** The application's job, the current owner profile and the notice period; null when the application is gone. */
    private Inputs read(GenerationRow g) {
        Optional<ApplicationRow> application = applications.find(g.applicationId());
        if (application.isEmpty()) {
            return null;
        }
        UUID jobId = application.get().jobId();
        JobRow job = jobId == null ? null : repository.findJob(jobId).orElse(null);
        StoredProfile profile = profiles.find().orElse(null);
        Integer notice = g.mentionNoticePeriod()
                ? preferences.find().map(p -> p.preferences().noticePeriodDays()).orElse(null)
                : null;
        return new Inputs(job, profile, notice);
    }

    /**
     * One kind's result.
     *
     * @param stored    the version was stored and recorded on the generation
     * @param discarded the application was deleted, so nothing was stored
     * @param failure   the per-kind failure, or null
     */
    private record KindResult(boolean stored, boolean discarded, GenerationFailure failure) {

        static final KindResult STORED = new KindResult(true, false, null);
        static final KindResult DISCARDED = new KindResult(false, true, null);

        static KindResult failed(GenerationFailure failure) {
            return new KindResult(false, false, failure);
        }
    }

    private KindResult generateKind(GenerationRow g, TailoringModel m, DocumentKind kind, Inputs in,
                                    ProfileFacts facts, FactCheck check, TailoringEngine.Options options,
                                    int profileVersion) {
        long t0 = System.nanoTime();
        TailoringOutcome<?> outcome = engine.generate(kind, m, in.job().facts(), facts, check, options);
        long latencyMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);

        KindResult result;
        String tag;
        List<Finding> findings = List.of();
        if (outcome instanceof TailoringOutcome.Generated<?> generated) {
            findings = generated.findings();
            NewVersion version = NewVersion.ai(g.applicationId(), kind, profileVersion, in.job().updatedAt(),
                    generated.content(), findings, m.label(), generated.inputHash());
            boolean saved = Boolean.TRUE.equals(tx.execute(s -> persist(g.id(), version)));
            result = saved ? KindResult.STORED : KindResult.DISCARDED;
            tag = saved ? outcome.tag() : "discarded";
        } else {
            GenerationFailure failure = ((TailoringOutcome.Failed<?>) outcome).failure();
            repository.finishKind(g.id(), kind, null, failure);
            result = KindResult.failed(failure);
            tag = outcome.tag();
        }
        recordMetrics(kind, m.label(), tag, latencyMs);
        TokenUsage usage = outcome.usage();
        log.info("Tailoring application={} generation={} kind={} model={} outcome={} latencyMs={} inputTokens={} "
                        + "outputTokens={} blocking={} warnings={}", g.applicationId(), g.id(), kind, m.label(), tag,
                latencyMs, usage == null ? null : usage.inputTokenCount(),
                usage == null ? null : usage.outputTokenCount(), count(findings, Severity.BLOCKING),
                count(findings, Severity.WARNING));
        return result;
    }

    /**
     * Stores one AI version and records it on the generation, in one short transaction under the
     * application row lock (the version number is MAX + 1). False when the application was deleted.
     */
    private boolean persist(UUID generationId, NewVersion version) {
        if (!applications.lockForWorker(version.applicationId())) {
            return false;
        }
        DocumentRef ref = documents.insertVersion(version);
        repository.finishKind(generationId, version.kind(), ref.id(), null);
        return true;
    }

    private void recordMetrics(DocumentKind kind, String label, String outcome, long latencyMs) {
        try {
            int slash = label.indexOf('/');
            Timer.builder(TIMER)
                    .tag("kind", kind.name())
                    .tag("outcome", outcome)
                    .tag("provider", slash < 0 ? label : label.substring(0, slash))
                    .tag("model", slash < 0 ? label : label.substring(slash + 1))
                    .register(meterRegistry)
                    .record(latencyMs, TimeUnit.MILLISECONDS);
        } catch (RuntimeException e) {
            log.debug("Could not record tailoring metrics ({})", e.getClass().getSimpleName());
        }
    }

    private static long count(List<Finding> findings, Severity severity) {
        return findings.stream().filter(f -> f.severity() == severity).count();
    }

    private static void logInterrupted(UUID generationId) {
        log.info("Generation generation={} interrupted by shutdown; will re-run on next start", generationId);
    }

    /** Exception class names along the cause chain, plus the SQLState of a database error. Never messages. */
    static String describe(Throwable failure) {
        StringBuilder sb = new StringBuilder();
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        int depth = 0;
        for (Throwable t = failure; t != null && depth < MAX_CAUSE_DEPTH && seen.add(t); t = t.getCause(), depth++) {
            if (!sb.isEmpty()) {
                sb.append(" <- ");
            }
            sb.append(t.getClass().getSimpleName());
            if (t instanceof SQLException sql && sql.getSQLState() != null) {
                sb.append("[SQLState ").append(sql.getSQLState()).append(']');
            }
        }
        return sb.toString();
    }

    private void safeFail(UUID generationId, GenerationFailure reason, String modelLabel) {
        try {
            repository.fail(generationId, reason, modelLabel, clock.instant());
        } catch (RuntimeException e) {
            log.error("Could not mark generation {} as failed ({}); it will be retried after a restart", generationId,
                    describe(e));
        }
    }
}
