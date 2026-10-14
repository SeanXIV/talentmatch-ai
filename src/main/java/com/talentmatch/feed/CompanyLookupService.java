package com.talentmatch.feed;

import com.talentmatch.feed.source.BoardInfo;
import com.talentmatch.feed.source.SourceKind;
import com.talentmatch.service.FieldErrors;
import com.talentmatch.service.exception.FeedLookupRateLimitedException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Finds the job boards of a company by name ({@code POST /api/feed/sources/lookup}, step 12). The name is
 * turned into board-name guesses ({@link BoardSlugs}); each guess is checked on Greenhouse, Lever (global
 * instance only) and Ashby, most specific guess first, within {@code talentmatch.feed.lookup.budget}.
 * <ul>
 *   <li><b>Read-only.</b> Never creates a source; the owner adds a found board with
 *       {@code POST /api/feed/sources}. A board already on the watchlist is reported from the database
 *       without an HTTP call.</li>
 *   <li>Adzuna is never queried, so a lookup never uses the daily Adzuna budget.</li>
 *   <li>Allowed while the feed is disabled (like adding a source with a board check).</li>
 *   <li>One lookup at a time, and at least {@code min-interval} between two starts: 429
 *       FEED_LOOKUP_RATE_LIMITED with Retry-After. Invalid requests (400) don't count.</li>
 *   <li>A board that couldn't be checked (provider trouble, every probe worker busy, budget spent) is
 *       listed in {@code unchecked} with a reason; {@code complete} is false when the budget ran out.</li>
 * </ul>
 * Logs counts only, never the company name or the guessed board names.
 */
@Service
public class CompanyLookupService {

    private static final Logger log = LoggerFactory.getLogger(CompanyLookupService.class);

    /** The providers checked, in order. Adzuna is a search, not a board, and costs budget: never checked. */
    static final List<SourceKind> KINDS = List.of(SourceKind.GREENHOUSE, SourceKind.LEVER, SourceKind.ASHBY);
    /** Less time than this left in the budget: the remaining boards are not checked. */
    static final Duration MIN_PROBE_TIME = Duration.ofSeconds(1);

    /** {@link Unchecked#reason()}: every probe worker was busy, or the check failed unexpectedly. */
    public static final String REASON_BUSY = "BUSY";
    /** {@link Unchecked#reason()}: the lookup's time budget ran out before this board was checked. */
    public static final String REASON_BUDGET_EXCEEDED = "BUDGET_EXCEEDED";
    /** {@link Found#warning()} of a board that is already a source. */
    public static final String ALREADY_WATCHED = "Already on your watchlist.";

    private final SourceProber prober;
    private final FeedSourceRepository repository;
    private final FeedProperties.Lookup settings;
    private final Duration probeTimeout;
    private final Clock clock;
    private final LongSupplier nanoTime;

    private final Object gate = new Object();
    private boolean running;
    private Instant lastStart;

    @Autowired
    public CompanyLookupService(SourceProber prober, FeedSourceRepository repository, FeedProperties properties,
                                Clock clock) {
        this(prober, repository, properties, clock, System::nanoTime);
    }

    /** For tests: {@code nanoTime} drives the budget (and the logged latency). */
    CompanyLookupService(SourceProber prober, FeedSourceRepository repository, FeedProperties properties,
                         Clock clock, LongSupplier nanoTime) {
        this.prober = prober;
        this.repository = repository;
        this.settings = properties.lookup();
        this.probeTimeout = properties.probeTimeout();
        this.clock = clock;
        this.nanoTime = nanoTime;
    }

    /**
     * @param companyName the name as given (normalized like a source's company name)
     * @param candidates  the board names tried, most specific first
     * @param found       boards that exist (or are already watched), in the order checked
     * @param unchecked   boards that couldn't be checked, in the order tried
     * @param complete    false when the time budget ran out before every board was checked
     */
    public record LookupResult(String companyName, List<String> candidates, List<Found> found,
                               List<Unchecked> unchecked, boolean complete) {
    }

    /**
     * A board that exists.
     *
     * @param companyName  the provider's name for the board (Greenhouse only), or the source's name when
     *                     already watched; otherwise null
     * @param openPostings the open postings when known: Ashby boards, empty boards (0) and watched sources;
     *                     otherwise null
     * @param warning      {@link BoardInfo#NO_OPEN_POSTINGS}, {@link #ALREADY_WATCHED} or null
     * @param sourceId     the existing source when the board is already watched; otherwise null
     */
    public record Found(SourceKind kind, String boardToken, String companyName, Integer openPostings,
                        String warning, UUID sourceId) {
    }

    /**
     * A board that couldn't be checked.
     *
     * @param reason a provider failure ({@code TIMEOUT, NETWORK, SERVER_ERROR, RATE_LIMITED, UNAUTHORIZED,
     *               INVALID_RESPONSE, TOO_LARGE}), {@link #REASON_BUSY} or {@link #REASON_BUDGET_EXCEEDED}
     */
    public record Unchecked(SourceKind kind, String boardToken, String reason) {
    }

    /**
     * @throws com.talentmatch.service.exception.RequestValidationException 400 on {@code companyName}
     * @throws FeedLookupRateLimitedException 429 while another lookup runs or within the minimum interval
     */
    public LookupResult lookup(String rawCompanyName) {
        String companyName = FeedSourceService.companyName(rawCompanyName);
        List<String> candidates = List.of();
        FieldErrors errors = new FieldErrors();
        if (companyName == null) {
            errors.add("companyName", "companyName is required: the company's name as on its careers page, "
                    + "e.g. \"Acme Payments\".");
        } else if (companyName.length() > FeedSourceService.MAX_COMPANY_NAME_LENGTH) {
            errors.maxLength("companyName", companyName, FeedSourceService.MAX_COMPANY_NAME_LENGTH, "companyName");
        } else {
            candidates = BoardSlugs.candidates(companyName, settings.maxCandidates());
            if (candidates.isEmpty() && !BoardSlugs.hasLettersOrDigits(companyName)) {
                errors.add("companyName", "companyName has no letters or digits (a–z, 0–9) to build a board name "
                        + "from. Add the source with the boardToken from its board URL instead.");
            } else if (candidates.isEmpty()) {
                // Letters or digits, but every guess fails SourceKeys.isValidToken (e.g. one 101+ char word).
                errors.add("companyName", "companyName gives no usable board name (board names are at most "
                        + "100 characters of a–z, 0–9 and -). Add the source with the boardToken from its board "
                        + "URL instead.");
            }
        }
        errors.throwIfAny();

        begin();
        try {
            return run(companyName, candidates);
        } finally {
            end();
        }
    }

    private LookupResult run(String companyName, List<String> candidates) {
        long started = nanoTime.getAsLong();
        long deadline = started + settings.budget().toNanos();
        List<Found> found = new ArrayList<>();
        List<Unchecked> unchecked = new ArrayList<>();
        int probes = 0;
        boolean complete = true;
        for (String slug : candidates) {
            for (SourceKind kind : KINDS) {
                Optional<FeedSource> existing = repository.findByKey(SourceKeys.sourceKey(kind, slug, Map.of()));
                if (existing.isPresent()) {
                    FeedSource source = existing.get();
                    found.add(new Found(kind, source.boardToken(), source.companyName(), source.openPostings(),
                            ALREADY_WATCHED, source.id()));
                    continue;
                }
                long remaining = deadline - nanoTime.getAsLong();
                if (remaining < MIN_PROBE_TIME.toNanos()) {
                    unchecked.add(new Unchecked(kind, slug, REASON_BUDGET_EXCEEDED));
                    complete = false;
                    continue;
                }
                Duration wait = Duration.ofNanos(remaining);
                probes++;
                SourceProber.Result result = prober.probe(kind, slug, Map.of(),
                        wait.compareTo(probeTimeout) < 0 ? wait : probeTimeout);
                if (result instanceof SourceProber.Found hit) {
                    found.add(found(kind, slug, hit.info()));
                } else if (result instanceof SourceProber.Unchecked miss) {
                    unchecked.add(new Unchecked(kind, slug,
                            miss.failure() == null ? REASON_BUSY : miss.failure().name()));
                }
            }
        }
        log.info("Feed lookup candidates={} probes={} found={} unchecked={} complete={} latencyMs={}",
                candidates.size(), probes, found.size(), unchecked.size(), complete,
                TimeUnit.NANOSECONDS.toMillis(nanoTime.getAsLong() - started));
        return new LookupResult(companyName, candidates, List.copyOf(found), List.copyOf(unchecked), complete);
    }

    private static Found found(SourceKind kind, String slug, BoardInfo info) {
        String name = FeedSourceService.companyName(info.companyName());
        if (name != null && name.length() > FeedSourceService.MAX_COMPANY_NAME_LENGTH) {
            name = name.substring(0, FeedSourceService.MAX_COMPANY_NAME_LENGTH).strip();
        }
        return new Found(kind, slug, name, info.openPostings(), info.warning(), null);
    }

    // ------------------------------------------------------------------ rate gate

    /** Takes the lookup slot, or throws 429. */
    private void begin() {
        synchronized (gate) {
            if (running) {
                throw new FeedLookupRateLimitedException(ceilSeconds(settings.budget()), true, intervalSeconds());
            }
            Instant now = clock.instant();
            if (lastStart != null) {
                Instant allowedAt = lastStart.plus(settings.minInterval());
                if (now.isBefore(allowedAt)) {
                    throw new FeedLookupRateLimitedException(ceilSeconds(Duration.between(now, allowedAt)), false,
                            intervalSeconds());
                }
            }
            running = true;
            lastStart = now;
        }
    }

    private void end() {
        synchronized (gate) {
            running = false;
        }
    }

    private int intervalSeconds() {
        return ceilSeconds(settings.minInterval());
    }

    private static int ceilSeconds(Duration duration) {
        long millis = Math.max(0, duration.toMillis());
        return (int) Math.max(1, (millis + 999) / 1000);
    }
}
