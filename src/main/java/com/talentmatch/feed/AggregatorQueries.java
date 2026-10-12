package com.talentmatch.feed;

import com.talentmatch.feed.source.SourceKind;
import com.talentmatch.feed.source.SourceProperties;
import com.talentmatch.preferences.JobPreferences;
import com.talentmatch.preferences.PreferencesRepository;
import com.talentmatch.profile.OwnerProfileRepository;
import com.talentmatch.profile.ProfileDocument;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Keeps the derived Adzuna searches ({@code managed_by = 'PREFERENCES'}) in step with the job
 * preferences and the confirmed profile (§4.11, decision i). Reconciled by source key: missing
 * searches are inserted (due at once), obsolete ones deleted with their postings and the feed jobs
 * left without a posting, unchanged ones kept (with their postings and schedule).
 * <ul>
 *   <li>Runs at startup, after preferences are saved, after a profile is confirmed, and when the
 *       processor's refresh check sees a missed profile or preferences change. A failed run is
 *       retried by the next processor run ({@link #reconcileIfPending}).</li>
 *   <li>Without Adzuna keys nothing is created or deleted (existing derived searches are kept and
 *       their polls report that Adzuna isn't configured).</li>
 *   <li>A search the owner already added by hand (same key) is not duplicated.</li>
 * </ul>
 * Logs counts only, never the search terms (they come from the owner's data).
 */
@Service
public class AggregatorQueries {

    private static final Logger log = LoggerFactory.getLogger(AggregatorQueries.class);
    private static final Duration LOCK_TIMEOUT = Duration.ofSeconds(2);

    /** What one reconciliation changed. */
    public record Result(int added, int removed, int kept, int orphanedJobsDeleted) {
    }

    private final FeedSourceRepository sources;
    private final PreferencesRepository preferences;
    private final OwnerProfileRepository profiles;
    private final FeedStateRepository state;
    private final SourceProperties.Adzuna adzuna;
    private final TransactionTemplate tx;
    private volatile boolean pending;

    public AggregatorQueries(FeedSourceRepository sources, PreferencesRepository preferences,
                             OwnerProfileRepository profiles, FeedStateRepository state,
                             SourceProperties sourceProperties, PlatformTransactionManager transactionManager) {
        this.sources = sources;
        this.preferences = preferences;
        this.profiles = profiles;
        this.state = state;
        this.adzuna = sourceProperties.adzuna();
        this.tx = new TransactionTemplate(transactionManager);
    }

    /** {@link #reconcile()}; a failure is logged and left for the next processor run. Never throws. */
    public void reconcileSafely() {
        try {
            reconcile();
        } catch (RuntimeException e) {
            pending = true;
            log.warn("Feed: reconciling the derived Adzuna searches failed ({}); the next processor run retries",
                    SourcePoller.describe(e));
        }
    }

    /** Leaves a reconciliation to the next processor run (the caller can't run one now). */
    public void markPending() {
        pending = true;
    }

    /** Retries a failed reconciliation (called at the start of every processor run). Never throws. */
    public void reconcileIfPending() {
        if (pending) {
            reconcileSafely();
        }
    }

    /**
     * Inserts the missing derived searches and deletes the obsolete ones.
     *
     * @return what changed; all zeros (and nothing touched) when Adzuna isn't configured
     */
    public synchronized Result reconcile() {
        pending = false;
        if (!adzuna.configured()) {
            return new Result(0, 0, 0, 0);
        }
        Optional<PreferencesRepository.Stored> stored = preferences.find();
        JobPreferences prefs = stored.map(PreferencesRepository.Stored::preferences).orElse(null);
        String latestTitle = profiles.find().map(OwnerProfileRepository.StoredProfile::profile)
                .map(AggregatorQueries::latestTitle).orElse(null);

        Map<String, Map<String, String>> wanted = new LinkedHashMap<>();
        for (Map<String, String> options : DerivedQueries.of(prefs, latestTitle, adzuna.derivedQueries(),
                adzuna.country())) {
            wanted.put(SourceKeys.sourceKey(SourceKind.ADZUNA, null, options), options);
        }

        Result result = LockRetry.run("Derived Adzuna searches", () -> tx.execute(status -> {
            state.limitLockWait(LOCK_TIMEOUT);
            int removed = 0;
            int kept = 0;
            for (FeedSource existing : sources.findManagedByPreferences()) {
                if (wanted.remove(existing.sourceKey()) != null) {
                    kept++;
                } else if (sources.findByIdForUpdate(existing.id()).isPresent()) {
                    removed += sources.delete(existing.id());
                }
            }
            int orphans = removed > 0 ? sources.deleteOrphanedFeedJobs() : 0;
            int added = 0;
            for (Map.Entry<String, Map<String, String>> e : wanted.entrySet()) {
                if (sources.insertIfAbsent(new FeedSourceRepository.NewSource(e.getKey(), SourceKind.ADZUNA,
                        SourceManagedBy.PREFERENCES, null, null, e.getValue(), null)).isPresent()) {
                    added++;
                }
            }
            return new Result(added, removed, kept, orphans);
        }));
        if (result != null && (result.added() > 0 || result.removed() > 0)) {
            log.info("Feed: derived Adzuna searches reconciled: added={} removed={} kept={} orphanedJobsDeleted={}",
                    result.added(), result.removed(), result.kept(), result.orphanedJobsDeleted());
        }
        return result;
    }

    /** The most recent role's title (experience is most recent first); null when none. */
    static String latestTitle(ProfileDocument profile) {
        List<ProfileDocument.Experience> experience = profile == null ? null : profile.experience();
        if (experience == null || experience.isEmpty() || experience.get(0) == null) {
            return null;
        }
        String title = experience.get(0).title();
        return title == null || title.isBlank() ? null : title.strip();
    }
}
