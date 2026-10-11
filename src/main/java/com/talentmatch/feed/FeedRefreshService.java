package com.talentmatch.feed;

import com.talentmatch.feed.skills.SkillDictionary;
import com.talentmatch.preferences.PreferencesRepository;
import com.talentmatch.preferences.PreferencesSavedEvent;
import com.talentmatch.profile.OwnerProfileConfirmedEvent;
import com.talentmatch.profile.OwnerProfileRepository;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Refresh triggers (§4.11): when something every open feed job was evaluated against changes, the
 * open jobs get {@code process_after = now} and {@code feed_state} records what was applied.
 * Re-processing is safe: the notifiable rule never notifies twice, and a job whose skills, verdict
 * and score didn't change writes nothing new.
 * <ul>
 *   <li><b>Owner profile confirmed</b> ({@link OwnerProfileConfirmedEvent}): re-score.</li>
 *   <li><b>Preferences saved</b> ({@link PreferencesSavedEvent}): re-evaluate.</li>
 *   <li><b>{@link #reconcile}</b> (startup and every processor run): compares the current profile
 *       version, preferences version and skill-vocabulary fingerprint with {@code feed_state}; any
 *       difference (a missed or failed event, a restart, a skill or alias added or renamed) marks the
 *       open jobs, so the dictionary runs again on them.</li>
 * </ul>
 * Both events are handled <b>after the save committed</b>, in a transaction of their own
 * (deviation from §4.11, which put the preferences marking in the saving transaction: marking every
 * open job there made {@code PUT /api/preferences} deadlock with a concurrent poll and roll back).
 * The applied version is recorded only together with a successful marking; a failure is logged and
 * left to {@link #reconcile}, which the processor runs first thing (the processor's own after-commit
 * wake-up is ordered after these listeners). The save itself never fails because of the feed.
 *
 * <p>Every marker first locks the {@code feed_state} row, so two triggers don't mark the same jobs at
 * once, then locks the open jobs in {@code job_id} order ({@link FeedJobRepository#markOpenForProcessing}).
 * Locked rows are waited for (a processor transaction holding a job must finish before the job can be
 * re-marked), but every lock wait of a marker transaction is bounded ({@code lock_timeout}): a
 * long transaction holding an open job (or {@code feed_state}) makes the marker fail fast instead of
 * stalling the request or processor run behind it. The event markers and {@link #reconcile} retry a
 * lock conflict or timeout a few times ({@link LockRetry}, {@link #MARKER_LOCK_TIMEOUT} per wait); the
 * processor's own check ({@link #reconcileForRun}) tries once with {@link #RUN_LOCK_TIMEOUT} and the
 * run goes on without it. A marker that fails rolls back with its {@code feed_state} update, so the
 * difference is still there for the next comparison. Derived Adzuna queries (also regenerated on
 * profile and preference changes) arrive with step 9. Logs hold versions and counts only.
 */
@Service
public class FeedRefreshService {

    /** Order of the after-commit markers: before {@link FeedProcessor}'s wake-up listeners. */
    static final int MARKER_ORDER = 0;

    /**
     * Longest single lock wait of the event markers and of {@link #reconcile} (each of the
     * {@link LockRetry#ATTEMPTS} attempts). Processor job transactions and poll writes normally hold
     * a feed_job row for milliseconds; a marker that runs out leaves the change to the processor run
     * woken right after it ({@link FeedProcessor}'s listeners) or to the next sweep.
     */
    static final Duration MARKER_LOCK_TIMEOUT = Duration.ofSeconds(2);

    /**
     * Longest single lock wait of the processor's own comparison ({@link #reconcileForRun}), tried
     * once: a run never waits long for a job locked elsewhere.
     */
    static final Duration RUN_LOCK_TIMEOUT = Duration.ofSeconds(1);

    private static final Logger log = LoggerFactory.getLogger(FeedRefreshService.class);

    private final FeedJobRepository jobs;
    private final FeedStateRepository state;
    private final OwnerProfileRepository profiles;
    private final PreferencesRepository preferences;
    private final SkillDictionary dictionary;
    private final TransactionTemplate tx;
    private final TransactionTemplate newTx;
    private final Clock clock;

    public FeedRefreshService(FeedJobRepository jobs, FeedStateRepository state, OwnerProfileRepository profiles,
                              PreferencesRepository preferences, SkillDictionary dictionary,
                              PlatformTransactionManager transactionManager, Clock clock) {
        this.jobs = jobs;
        this.state = state;
        this.profiles = profiles;
        this.preferences = preferences;
        this.dictionary = dictionary;
        this.tx = new TransactionTemplate(transactionManager);
        this.newTx = new TransactionTemplate(transactionManager);
        this.newTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.clock = clock;
    }

    /**
     * After {@code PUT /api/preferences} committed, in a transaction of its own. A failure is logged
     * only: the next {@link #reconcile} sees the version difference and catches up.
     */
    @Order(MARKER_ORDER)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onPreferencesSaved(PreferencesSavedEvent event) {
        try {
            Integer marked = LockRetry.run("Feed refresh after preferences", () -> newTx.execute(status -> {
                state.limitLockWait(MARKER_LOCK_TIMEOUT);
                state.lock();
                int n = jobs.markOpenForProcessing(clock.instant());
                state.setPreferencesVersion(event.version());
                return n;
            }));
            log.info("Feed refresh: preferences version {} saved; {} open job(s) queued for re-evaluation",
                    event.version(), marked);
        } catch (RuntimeException e) {
            log.warn("Feed refresh after preferences version {} failed ({}); the next processor run catches up",
                    event.version(), SourcePoller.describe(e));
        }
    }

    /**
     * After the profile save committed, in a transaction of its own. A failure is logged only: the
     * next {@link #reconcile} sees the version difference and catches up.
     */
    @Order(MARKER_ORDER)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onProfileConfirmed(OwnerProfileConfirmedEvent event) {
        try {
            Integer marked = LockRetry.run("Feed refresh after profile", () -> newTx.execute(status -> {
                state.limitLockWait(MARKER_LOCK_TIMEOUT);
                state.lock();
                int n = jobs.markOpenForProcessing(clock.instant());
                state.setProfileVersion(event.version());
                return n;
            }));
            log.info("Feed refresh: profile version {} confirmed; {} open job(s) queued for re-scoring",
                    event.version(), marked);
        } catch (RuntimeException e) {
            log.warn("Feed refresh after profile version {} failed ({}); the next processor run catches up",
                    event.version(), SourcePoller.describe(e));
        }
    }

    /** {@link #reconcile(String)} with the current vocabulary fingerprint (rebuilds the dictionary if it changed). */
    public boolean reconcile() {
        return reconcile(dictionary.refresh().fingerprint());
    }

    /**
     * Compares what the open jobs were last marked for with the current profile version, preferences
     * version and vocabulary fingerprint; marks the open jobs when anything differs. In a transaction
     * of its own, every lock wait is bounded by {@link #MARKER_LOCK_TIMEOUT} and a lock conflict or
     * timeout is retried a few times; inside a caller's transaction it joins it and leaves that
     * transaction's lock settings alone (no bound, no retry).
     *
     * @param vocabFingerprint the current skill-vocabulary fingerprint (null = don't compare it)
     * @return true when the open jobs were marked
     */
    public boolean reconcile(String vocabFingerprint) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            // A lost lock aborts the caller's transaction: no retry is possible there.
            return Boolean.TRUE.equals(tx.execute(status -> reconcileLocked(vocabFingerprint)));
        }
        Supplier<Boolean> attempt = () -> tx.execute(status -> {
            state.limitLockWait(MARKER_LOCK_TIMEOUT);
            return reconcileLocked(vocabFingerprint);
        });
        return Boolean.TRUE.equals(LockRetry.run("Feed reconcile", attempt));
    }

    /**
     * The processor's comparison at the start of a run: {@link #reconcile(String)} in a transaction of
     * its own, tried once, every lock wait bounded by {@link #RUN_LOCK_TIMEOUT}. A lock conflict or
     * timeout is thrown (the caller logs it and processes the due jobs anyway); it rolled back with
     * any {@code feed_state} change, so the next run's comparison still sees the difference.
     *
     * @return true when the open jobs were marked
     */
    boolean reconcileForRun(String vocabFingerprint) {
        return Boolean.TRUE.equals(newTx.execute(status -> {
            state.limitLockWait(RUN_LOCK_TIMEOUT);
            return reconcileLocked(vocabFingerprint);
        }));
    }

    private boolean reconcileLocked(String vocabFingerprint) {
        FeedStateRepository.State applied = state.lock();
        Integer profileVersion = profiles.findRef().map(OwnerProfileRepository.OwnerRef::version).orElse(null);
        Integer preferencesVersion = preferences.findVersion().orElse(null);
        List<String> changed = new ArrayList<>(3);
        if (!Objects.equals(profileVersion, applied.appliedProfileVersion())) {
            state.setProfileVersion(profileVersion);
            changed.add("profile " + applied.appliedProfileVersion() + "->" + profileVersion);
        }
        if (!Objects.equals(preferencesVersion, applied.appliedPreferencesVersion())) {
            state.setPreferencesVersion(preferencesVersion);
            changed.add("preferences " + applied.appliedPreferencesVersion() + "->" + preferencesVersion);
        }
        if (vocabFingerprint != null && !vocabFingerprint.equals(applied.vocabFingerprint())) {
            state.setVocabFingerprint(vocabFingerprint);
            changed.add("skill vocabulary");
        }
        if (changed.isEmpty()) {
            return false;
        }
        int n = jobs.markOpenForProcessing(clock.instant());
        log.info("Feed refresh: {} changed; {} open job(s) queued for processing", String.join(", ", changed), n);
        return true;
    }
}
