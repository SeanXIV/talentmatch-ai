package com.talentmatch.feed;

import com.talentmatch.domain.scoring.MatchEvaluation;
import com.talentmatch.feed.skills.SkillDictionary;
import com.talentmatch.notify.Channel;
import com.talentmatch.notify.NotificationChannels;
import com.talentmatch.notify.NotificationRepository;
import com.talentmatch.notify.NotificationSettingsRepository;
import com.talentmatch.preferences.FeedFacts;
import com.talentmatch.preferences.JobPreferences;
import com.talentmatch.preferences.PreferencesRepository;
import com.talentmatch.preferences.PreferencesSavedEvent;
import com.talentmatch.profile.OwnerProfileConfirmedEvent;
import com.talentmatch.profile.OwnerProfileRepository;
import com.talentmatch.repository.MatchJdbcRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Processes feed jobs whose {@code process_after} is due (§1.2), one transaction per job:
 * <ol>
 *   <li>dictionary skills from the title and description ({@link SkillDictionary}, never invents);</li>
 *   <li>merged with the AI skills into the effective skills; {@code job_skill} gets only the
 *       differences (the V2 triggers then mark cached matches stale);</li>
 *   <li>the preference filter (PASS / FILTERED with reasons and flags);</li>
 *   <li>{@link FeedScorer}: the owner's score with the existing scoring, when an owner profile exists
 *       and the job has skills;</li>
 *   <li>the notifiable rule ({@link Notifiability}): a PENDING {@code feed_notification} row
 *       ({@code ON CONFLICT DO NOTHING}, at most one per job, ever). Sending is the dispatcher's job
 *       (step 8);</li>
 *   <li>{@code process_after = NULL}, with the versions evaluated against.</li>
 * </ol>
 * Jobs are claimed with {@code FOR NO KEY UPDATE SKIP LOCKED}, so a poll writing a job, or a second
 * processor run, never blocks a run (the job is simply processed later). A job whose processing
 * fails waits {@code processor.retry-delay} and is retried; the others go on.
 *
 * <p><b>Threads.</b> {@link #processDue()} is the synchronous entry point (tests call it). In the
 * background the processor runs on its single {@code feed-proc-} thread, started by {@link #wake()}
 * (after a poll wrote jobs, after a profile or preferences change) and by a sweep every
 * {@code processor.sweep}. Background runs happen only while the scheduler runs
 * ({@code talentmatch.feed.enabled} and {@code talentmatch.feed.scheduler.enabled}); with the scheduler
 * off, {@code wake()} does nothing and jobs wait for {@code processDue()}. With the feed disabled
 * nothing is processed.
 *
 * <p>Each run first checks the skill vocabulary, profile and preferences against what was applied
 * ({@link FeedRefreshService#reconcileForRun}, with a short bounded lock wait; losing it doesn't stop
 * the run, the next run checks again). The vocabulary, preferences, owner profile version
 * and notification settings a job is evaluated against are read in that job's transaction, after it
 * is locked, so a change saved during a run is either used or re-marks the job (see {@code Inputs}).
 * The job's match lock is held from before its {@code job_skill} rows change until the commit.
 * Logs hold job ids, counts and scores only: never a
 * title, description or anything from the owner's profile.
 */
@Component
public class FeedProcessor {

    private static final Logger log = LoggerFactory.getLogger(FeedProcessor.class);

    private final FeedJobRepository jobs;
    private final SkillDictionary dictionary;
    private final FeedRefreshService refresh;
    private final PreferencesRepository preferences;
    private final OwnerProfileRepository profiles;
    private final FeedScorer scorer;
    private final MatchJdbcRepository matches;
    private final NotificationSettingsRepository settings;
    private final NotificationRepository notifications;
    private final NotificationChannels channels;
    private final FeedProperties properties;
    private final TransactionTemplate tx;
    private final ThreadPoolTaskExecutor executor;
    private final Clock clock;

    public FeedProcessor(FeedJobRepository jobs, SkillDictionary dictionary, FeedRefreshService refresh,
                         PreferencesRepository preferences, OwnerProfileRepository profiles, FeedScorer scorer,
                         MatchJdbcRepository matches,
                         NotificationSettingsRepository settings, NotificationRepository notifications,
                         NotificationChannels channels, FeedProperties properties,
                         PlatformTransactionManager transactionManager,
                         @Qualifier(FeedConfig.PROCESSOR_EXECUTOR) ThreadPoolTaskExecutor executor, Clock clock) {
        this.jobs = jobs;
        this.dictionary = dictionary;
        this.refresh = refresh;
        this.preferences = preferences;
        this.profiles = profiles;
        this.scorer = scorer;
        this.matches = matches;
        this.settings = settings;
        this.notifications = notifications;
        this.channels = channels;
        this.properties = properties;
        this.tx = new TransactionTemplate(transactionManager);
        this.executor = executor;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ entry points

    /**
     * Starts a background run on the processor thread, unless background processing is off (the
     * scheduler is off). A run already queued covers this wake-up. Never throws, never blocks.
     */
    public void wake() {
        if (!properties.schedulerRunning()) {
            return;
        }
        try {
            executor.execute(this::backgroundRun);
        } catch (TaskRejectedException e) {
            // Shutting down; the jobs stay due and are processed after the next start.
            log.debug("Feed processor wake-up rejected ({})", e.getClass().getName());
        }
    }

    /**
     * Ordered after {@link FeedRefreshService}'s markers: the run starts once the open jobs are marked,
     * or, when marking failed, its {@link FeedRefreshService#reconcile} catches up.
     */
    @Order(Ordered.LOWEST_PRECEDENCE)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onProfileConfirmed(OwnerProfileConfirmedEvent event) {
        wake();
    }

    /** Ordered after {@link FeedRefreshService}'s markers (see {@link #onProfileConfirmed}). */
    @Order(Ordered.LOWEST_PRECEDENCE)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onPreferencesSaved(PreferencesSavedEvent event) {
        wake();
    }

    /**
     * Processes every due job now, on the calling thread, in batches of {@code processor.batch-size}.
     * Safe to call while a background run is in progress (claims skip each other's jobs).
     *
     * @return the number of jobs processed (0 when the feed is disabled)
     */
    public int processDue() {
        if (!properties.enabled()) {
            return 0;
        }
        prepare();
        int batchSize = properties.processor().batchSize();
        int total = 0;
        while (!Thread.currentThread().isInterrupted()) {
            List<UUID> due = jobs.findDue(clock.instant(), batchSize);
            if (due.isEmpty()) {
                break;
            }
            int done = 0;
            for (UUID jobId : due) {
                if (Thread.currentThread().isInterrupted()) {
                    break;
                }
                if (processOne(jobId)) {
                    done++;
                }
            }
            total += done;
            // A batch with no progress (all locked elsewhere or failing) ends the run; the sweep returns.
            if (done == 0 || due.size() < batchSize) {
                break;
            }
        }
        if (total > 0) {
            log.info("Feed processor: processed={}", total);
        }
        return total;
    }

    /**
     * Processes one job now if it is due (its {@code process_after} is set), whatever the time.
     *
     * @return true when it was processed; false when it isn't pending, is locked, or failed
     */
    public boolean process(UUID jobId) {
        if (!properties.enabled()) {
            return false;
        }
        prepare();
        return processOne(jobId);
    }

    // ------------------------------------------------------------------ one run

    /**
     * Once per run: the refresh triggers catch up with what changed since the last run. The check
     * waits at most {@link FeedRefreshService#RUN_LOCK_TIMEOUT} for a lock and is not retried: when a
     * job (or {@code feed_state}) is locked longer, it is logged and the due jobs are processed anyway
     * (each reads its inputs under its own lock, so they are evaluated correctly). Nothing was
     * recorded as applied, so the next run's check marks the open jobs then. Any other failure is
     * not a contention problem and ends the run, as before.
     */
    private void prepare() {
        String fingerprint = dictionary.refresh().fingerprint();
        try {
            refresh.reconcileForRun(fingerprint);
        } catch (RuntimeException e) {
            if (!LockRetry.isLockConflict(e)) {
                throw e;
            }
            log.warn("Feed processor: the refresh check lost a lock conflict ({}); processing the due jobs, "
                    + "the next run checks again", SourcePoller.describe(e));
        }
    }

    /**
     * What one job is evaluated against, read inside its transaction after the job is locked, never
     * once per run: a refresh marker (§4.11) that records a newer preferences or profile version (or
     * vocabulary) waits for this lock and marks the job again after this transaction, or it committed
     * before the lock was taken and these reads see the change. Either way the job doesn't keep a
     * verdict computed against something older than what {@code feed_state} says was applied.
     *
     * @param owner the owner's candidate and profile version; null = no profile (nothing is scored)
     */
    private record Inputs(SkillDictionary.Snapshot vocabulary, JobPreferences preferences, Integer preferencesVersion,
                          OwnerProfileRepository.OwnerRef owner, NotificationSettingsRepository.Settings settings,
                          boolean channelConfigured) {
    }

    /** Single-row reads (the vocabulary snapshot is cached; only its fingerprint is read). */
    private Inputs readInputs() {
        Optional<PreferencesRepository.Stored> stored = preferences.find();
        NotificationSettingsRepository.Settings s = settings.find();
        return new Inputs(dictionary.refresh(),
                stored.map(PreferencesRepository.Stored::preferences).orElse(null),
                stored.map(PreferencesRepository.Stored::version).orElse(null),
                profiles.findRef().orElse(null),
                s,
                channels.configured(s.channel()));
    }

    /** The result of a committed job transaction. */
    private record Outcome(int skills, boolean pass, Double score, Notifiability.Decision decision, boolean queued,
                           Channel channel) {
    }

    private boolean processOne(UUID jobId) {
        Outcome outcome;
        try {
            outcome = tx.execute(status -> processLocked(jobId));
        } catch (RuntimeException e) {
            Instant retryAt = clock.instant().plus(properties.processor().retryDelay());
            log.warn("Feed processing job={} failed ({}); retrying after {}", jobId, SourcePoller.describe(e), retryAt);
            try {
                jobs.deferProcessing(jobId, retryAt);
            } catch (RuntimeException again) {
                log.warn("Feed processing job={}: could not defer it ({})", jobId, SourcePoller.describe(again));
            }
            return false;
        }
        if (outcome == null) {
            return false;                                      // not pending any more, or locked by a poll
        }
        if (outcome.queued()) {
            log.info("Feed notification queued job={} score={}", jobId, format(outcome.score()));
            // Step 8: the notification dispatcher is woken here.
        } else if (outcome.decision().reason() == Notifiability.Reason.CHANNEL_NOT_CONFIGURED) {
            log.info("Feed notification skipped job={} score={} reason={}_not_configured", jobId,
                    format(outcome.score()), outcome.channel().name().toLowerCase(Locale.ROOT));
        }
        log.debug("Feed processed job={} skills={} verdict={} score={} notify={}", jobId, outcome.skills(),
                outcome.pass() ? "PASS" : "FILTERED", format(outcome.score()),
                outcome.decision().shouldNotify() ? "yes" : outcome.decision().reason());
        return true;
    }

    /** Inside the job's transaction; null when the job can't be claimed. */
    private Outcome processLocked(UUID jobId) {
        Optional<FeedJobRepository.ProcessingRow> claimed = jobs.lockForProcessing(jobId);
        if (claimed.isEmpty()) {
            return null;
        }
        FeedJobRepository.ProcessingRow row = claimed.get();
        // The job's match lock (a transaction-level advisory lock, as MatchService takes it) before its
        // skills change: a concurrent GET /api/jobs/{id}/matches scores either before this transaction
        // or after it commits, never with the old skills while they are being replaced. Taken after the
        // row lock, as FeedScorer always did (it re-acquires it below, which is immediate). Throws when
        // held too long: the job is retried later.
        matches.lockJob(jobId);
        Inputs in = readInputs();

        // 1–3: skills and preferences
        FeedFacts facts = new FeedFacts(row.title(), row.description(), row.countryCodes(), row.workplace(),
                row.locationText(), row.seniority(), row.salaryMax(), row.salaryCurrency(), row.salaryPeriod(),
                row.salaryEstimated());
        FeedEvaluation.Result result = FeedEvaluation.evaluate(row.title(), row.description(),
                FeedJobJson.skills(row.aiSkillsJson()), in.vocabulary().matcher(), in.vocabulary().names(), facts,
                in.preferences());
        jobs.syncJobSkills(jobId, result.jobSkills());

        // 4: the owner's score (same transaction, under the job's match lock)
        Optional<MatchEvaluation> evaluation = in.owner() == null || !result.matchable() ? Optional.empty()
                : scorer.score(jobId, in.owner().candidateId());
        Double score = evaluation.map(MatchEvaluation::score).orElse(null);

        // 5: notifiable → one PENDING row, at most once per job
        Instant now = clock.instant();
        NotificationSettingsRepository.Settings s = in.settings();
        Channel channel = s.channel();
        Notifiability.Decision decision = Notifiability.decide(new Notifiability.Input(row.closedAt(), row.baseline(),
                row.firstSeenAt(), result.verdict().pass(), score, s.enabled(), s.minScore(), in.channelConfigured(),
                notifications.existsForJob(jobId)), now, properties.freshWindow());
        boolean queued = decision.shouldNotify() && notifications.insertIfAbsent(jobId, channel, score, now);

        // 6: AI enrichment of PENDING jobs is woken here with step 10.

        jobs.markProcessed(jobId, new FeedJobRepository.Processed(
                FeedJobJson.write(result.dictionarySkills()),
                result.verdict().pass() ? "PASS" : "FILTERED",
                FeedJobJson.writeNames(result.verdict().reasons()),
                FeedJobJson.writeNames(result.verdict().flags()),
                in.preferencesVersion(),
                in.owner() == null ? null : in.owner().version(),
                evaluation.isPresent() ? now : null));
        return new Outcome(result.skills().size(), result.verdict().pass(), score, decision, queued, channel);
    }

    private void backgroundRun() {
        try {
            processDue();
        } catch (RuntimeException e) {
            log.warn("Feed processor run failed ({}); the next sweep retries", SourcePoller.describe(e));
        }
    }

    private static String format(Double score) {
        return score == null ? "none" : String.format(Locale.ROOT, "%.4f", score);
    }
}
