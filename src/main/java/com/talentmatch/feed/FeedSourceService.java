package com.talentmatch.feed;

import com.talentmatch.feed.source.BoardInfo;
import com.talentmatch.feed.source.LeverAdapter;
import com.talentmatch.feed.source.SourceFailure;
import com.talentmatch.feed.source.SourceKind;
import com.talentmatch.service.FieldErrors;
import com.talentmatch.service.Paging;
import com.talentmatch.service.exception.ConflictException;
import com.talentmatch.service.exception.NotFoundException;
import com.talentmatch.web.error.ErrorCode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The owner's watchlist of job sources (§5.1): add (with a board check), list, read, edit, delete.
 * <ul>
 *   <li><b>Add</b> runs outside any transaction: the board check is an HTTP call, and the insert is
 *       one statement ({@code ON CONFLICT (source_key) DO NOTHING}), so a duplicate, even a
 *       concurrent one, is a 409 naming the existing source.</li>
 *   <li>A board the provider says doesn't exist is a 400 on {@code boardToken}. A board that couldn't
 *       be checked (network, timeout, 5xx, rate limit, odd answer) is saved with a warning; the first
 *       poll checks it again.</li>
 *   <li>Edits and deletes lock the row; sources managed by the job preferences (derived Adzuna
 *       queries, step 9) can't be changed here (409 DATA_CONFLICT).</li>
 *   <li>Adzuna sources can't be added yet (step 9): 400 on {@code kind}.</li>
 * </ul>
 */
@Service
public class FeedSourceService {

    private static final Logger log = LoggerFactory.getLogger(FeedSourceService.class);

    static final int MAX_COMPANY_NAME_LENGTH = 200;
    private static final Pattern WHITESPACE_RUN = Pattern.compile("\\s+");
    private static final Pattern CONTROL = Pattern.compile("\\p{Cntrl}");

    private final FeedSourceRepository repository;
    private final SourceProber prober;
    private final FeedProperties properties;

    public FeedSourceService(FeedSourceRepository repository, SourceProber prober, FeedProperties properties) {
        this.repository = repository;
        this.prober = prober;
        this.properties = properties;
    }

    /** Provider options as sent: {@code leverInstance} (Lever); {@code what}, {@code where} (Adzuna). */
    public record SourceOptions(String leverInstance, String what, String where) {
    }

    /** A source to add. {@code verify} null means true. */
    public record NewSource(SourceKind kind, String boardToken, String companyName, SourceOptions options,
                            Integer pollIntervalSeconds, Boolean verify) {
    }

    /** The owner-editable fields (full replace): a null name or interval clears it; state is required. */
    public record SourceChanges(String companyName, FeedSourceState state, Integer pollIntervalSeconds) {
    }

    // ------------------------------------------------------------------ read

    @Transactional(readOnly = true)
    public Page<FeedSourceView> list(SourceKind kind, FeedSourceState state, int page, int size) {
        Pageable pageable = Paging.of(page, size);
        long total = repository.count(kind, state);
        List<FeedSourceView> content = total == 0 ? List.of()
                : repository.findPage(kind, state, pageable.getOffset(), pageable.getPageSize()).stream()
                        .map(source -> view(source, List.of())).toList();
        return new PageImpl<>(content, pageable, total);
    }

    @Transactional(readOnly = true)
    public FeedSourceView get(UUID id) {
        return view(repository.findById(id).orElseThrow(() -> NotFoundException.feedSource(id)), List.of());
    }

    // ------------------------------------------------------------------ add

    /**
     * Validates, checks the board (unless {@code verify=false}) and inserts the source.
     *
     * @throws com.talentmatch.service.exception.RequestValidationException 400 for invalid fields or an
     *         unknown board
     * @throws ConflictException 409 FEED_SOURCE_ALREADY_EXISTS when the board is already watched
     */
    public FeedSourceView create(NewSource request) {
        NewSource in = request == null ? new NewSource(null, null, null, null, null, null) : request;
        SourceKind kind = in.kind();
        String token = in.boardToken() == null ? null : in.boardToken().strip();
        String companyName = companyName(in.companyName());
        SourceOptions options = in.options() == null ? new SourceOptions(null, null, null) : in.options();

        FieldErrors errors = new FieldErrors();
        if (kind == null) {
            errors.add("kind", "kind is required: GREENHOUSE, LEVER or ASHBY.");
        } else if (kind == SourceKind.ADZUNA) {
            errors.add("kind", "Adzuna sources can't be added yet. Add a company job board instead: "
                    + "GREENHOUSE, LEVER or ASHBY.");
        } else {
            validateToken(errors, kind, token);
            validateOptions(errors, kind, options);
            validateInterval(errors, kind, in.pollIntervalSeconds());
        }
        validateCompanyName(errors, companyName);
        errors.throwIfAny();

        Map<String, String> storedOptions = storedOptions(kind, options);
        String key = SourceKeys.sourceKey(kind, token, storedOptions);
        repository.findByKey(key).ifPresent(existing -> {
            throw alreadyExists(existing);
        });

        List<String> warnings = new ArrayList<>();
        boolean verify = in.verify() == null || in.verify();
        if (verify) {
            SourceProber.Result result = prober.probe(kind, token, storedOptions);
            if (result instanceof SourceProber.NotFound) {
                new FieldErrors().add("boardToken", notFoundMessage(kind, token, storedOptions)).throwIfAny();
            } else if (result instanceof SourceProber.Found found) {
                BoardInfo info = found.info();
                if (info.warning() != null) {
                    warnings.add(info.warning());
                }
                if (companyName == null) {
                    companyName = truncate(companyName(info.companyName()), MAX_COMPANY_NAME_LENGTH);
                }
            } else if (result instanceof SourceProber.Unchecked unchecked) {
                warnings.add(uncheckedWarning(kind, unchecked.failure()));
            }
        } else {
            warnings.add("The board wasn't checked (verify=false). If the token is wrong, the first poll "
                    + "reports NOT_FOUND in lastStatus.");
        }

        Optional<FeedSource> inserted = repository.insertIfAbsent(new FeedSourceRepository.NewSource(
                key, kind, SourceManagedBy.OWNER, companyName, token, storedOptions, in.pollIntervalSeconds()));
        if (inserted.isEmpty()) {
            // Added concurrently (after the check above): report the winner.
            throw repository.findByKey(key).map(FeedSourceService::alreadyExists)
                    .orElseGet(() -> new ConflictException(ErrorCode.DATA_CONFLICT,
                            "The source changed while it was being added. Reload and try again."));
        }
        FeedSource source = inserted.get();
        log.info("Feed source added id={} kind={} verified={} warnings={}", source.id(), kind, verify,
                warnings.size());
        return view(source, warnings);
    }

    // ------------------------------------------------------------------ edit / delete

    @Transactional
    public FeedSourceView update(UUID id, SourceChanges request) {
        FeedSource existing = repository.findByIdForUpdate(id).orElseThrow(() -> NotFoundException.feedSource(id));
        requireOwnerManaged(existing);

        SourceChanges in = request == null ? new SourceChanges(null, null, null) : request;
        String companyName = companyName(in.companyName());
        FieldErrors errors = new FieldErrors();
        validateCompanyName(errors, companyName);
        if (in.state() == null) {
            errors.add("state", "state is required: ACTIVE or PAUSED.");
        }
        validateInterval(errors, existing.kind(), in.pollIntervalSeconds());
        errors.throwIfAny();

        boolean resumed = existing.state() == FeedSourceState.PAUSED && in.state() == FeedSourceState.ACTIVE;
        FeedSource updated = repository.updateSettings(id, companyName, in.state(), in.pollIntervalSeconds(), resumed)
                .orElseThrow(() -> NotFoundException.feedSource(id));
        log.info("Feed source updated id={} state={} resumed={}", id, updated.state(), resumed);
        return view(updated, List.of());
    }

    /** Deletes the source, its postings (cascade) and the feed jobs left with no posting (§3.3). */
    @Transactional
    public void delete(UUID id) {
        FeedSource existing = repository.findByIdForUpdate(id).orElseThrow(() -> NotFoundException.feedSource(id));
        requireOwnerManaged(existing);
        repository.delete(id);
        int orphans = repository.deleteOrphanedFeedJobs();
        log.info("Feed source deleted id={} kind={} orphanedJobsDeleted={}", id, existing.kind(), orphans);
    }

    // ------------------------------------------------------------------ helpers

    private FeedSourceView view(FeedSource source, List<String> warnings) {
        return new FeedSourceView(source,
                properties.intervals().effectiveSeconds(source.kind(), source.pollIntervalSeconds()), warnings);
    }

    private static void requireOwnerManaged(FeedSource source) {
        if (source.managedBy() == SourceManagedBy.PREFERENCES) {
            throw new ConflictException(ErrorCode.DATA_CONFLICT, "Source " + source.id()
                    + " is managed by your job preferences; change those instead (PUT /api/preferences).");
        }
    }

    private static void validateToken(FieldErrors errors, SourceKind kind, String token) {
        if (token == null || token.isEmpty()) {
            errors.add("boardToken", "boardToken is required: the board name from the " + display(kind)
                    + " URL (" + urlHint(kind) + ").");
        } else if (!SourceKeys.isValidToken(token)) {
            errors.add("boardToken", "boardToken may only contain letters, digits, '.', '_' and '-' (at most 100 "
                    + "characters), as in the " + display(kind) + " URL (" + urlHint(kind) + ").");
        }
    }

    private static void validateOptions(FieldErrors errors, SourceKind kind, SourceOptions options) {
        String instance = options.leverInstance();
        if (instance != null) {
            if (kind != SourceKind.LEVER) {
                errors.add("options.leverInstance", "options.leverInstance is only for LEVER sources.");
            } else {
                try {
                    SourceKeys.isLeverEu(instance);
                } catch (IllegalArgumentException e) {
                    errors.add("options.leverInstance",
                            "options.leverInstance must be \"eu\" (jobs.eu.lever.co) or \"global\" (jobs.lever.co).");
                }
            }
        }
        if (options.what() != null) {
            errors.add("options.what", "options.what is only for ADZUNA sources.");
        }
        if (options.where() != null) {
            errors.add("options.where", "options.where is only for ADZUNA sources.");
        }
    }

    private void validateInterval(FieldErrors errors, SourceKind kind, Integer seconds) {
        if (seconds == null) {
            return;
        }
        int min = properties.intervals().minSeconds(kind);
        if (seconds < min || seconds > FeedProperties.MAX_INTERVAL_SECONDS) {
            errors.add("pollIntervalSeconds", "pollIntervalSeconds must be between " + min + " and "
                    + FeedProperties.MAX_INTERVAL_SECONDS + " for " + display(kind) + " sources; leave it out for "
                    + "the default of " + properties.intervals().defaultSeconds(kind) + ".");
        }
    }

    private static void validateCompanyName(FieldErrors errors, String normalized) {
        errors.maxLength("companyName", normalized, MAX_COMPANY_NAME_LENGTH, "companyName");
    }

    /** Only the EU Lever instance is stored as an option; the global instance is the default. */
    private static Map<String, String> storedOptions(SourceKind kind, SourceOptions options) {
        if (kind == SourceKind.LEVER && SourceKeys.isLeverEu(options.leverInstance())) {
            return Map.of(LeverAdapter.INSTANCE_OPTION, SourceKeys.LEVER_EU);
        }
        return Map.of();
    }

    /** Trimmed, whitespace runs (line breaks included) collapsed, other control characters removed. */
    static String companyName(String raw) {
        if (raw == null) {
            return null;
        }
        String s = CONTROL.matcher(WHITESPACE_RUN.matcher(raw).replaceAll(" ")).replaceAll("").strip();
        return s.isEmpty() ? null : s;
    }

    private static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max).strip();
    }

    private static ConflictException alreadyExists(FeedSource existing) {
        return new ConflictException(ErrorCode.FEED_SOURCE_ALREADY_EXISTS, describe(existing)
                + " is already on your watchlist (source id " + existing.id() + ").");
    }

    private static String describe(FeedSource source) {
        boolean eu = SourceKeys.LEVER_EU.equals(source.options().get(LeverAdapter.INSTANCE_OPTION));
        return switch (source.kind()) {
            case GREENHOUSE -> "Greenhouse board '" + source.boardToken() + "'";
            case LEVER -> "Lever " + (eu ? "(EU) " : "") + "site '" + source.boardToken() + "'";
            case ASHBY -> "Ashby board '" + source.boardToken() + "'";
            case ADZUNA -> "This Adzuna search";
        };
    }

    private static String notFoundMessage(SourceKind kind, String token, Map<String, String> options) {
        return switch (kind) {
            case GREENHOUSE -> "Greenhouse has no job board '" + token + "'. Check the token in the board URL "
                    + "(boards.greenhouse.io/<token>).";
            case LEVER -> "Lever has no job site '" + token + "'"
                    + (options.isEmpty() ? "" : " on its EU instance")
                    + ". Check the name in the job site URL (jobs.lever.co/<site>, or jobs.eu.lever.co/<site> with "
                    + "options.leverInstance \"eu\"); Lever site names are case-sensitive.";
            case ASHBY -> "Ashby has no job board '" + token + "'. Check the name in the board URL "
                    + "(jobs.ashbyhq.com/<name>).";
            case ADZUNA -> "Adzuna sources can't be checked.";
        };
    }

    static String uncheckedWarning(SourceKind kind, SourceFailure.Kind failure) {
        String name = display(kind);
        String then = " it will be checked on the first poll.";
        if (failure == null) {
            return "Couldn't check the " + name + " board right now;" + then;
        }
        return switch (failure) {
            case RATE_LIMITED -> name + " is limiting requests right now, so the board wasn't checked;" + then;
            case UNAUTHORIZED -> name + " refused access to the board, so it wasn't checked;" + then;
            case INVALID_RESPONSE, TOO_LARGE -> name + " gave an unexpected answer, so the board wasn't checked;"
                    + then;
            case NOT_FOUND, SERVER_ERROR, NETWORK, TIMEOUT -> "Couldn't reach " + name + " to check the board;"
                    + then;
        };
    }

    static String display(SourceKind kind) {
        return switch (kind) {
            case GREENHOUSE -> "Greenhouse";
            case LEVER -> "Lever";
            case ASHBY -> "Ashby";
            case ADZUNA -> "Adzuna";
        };
    }

    private static String urlHint(SourceKind kind) {
        return switch (kind) {
            case GREENHOUSE -> "boards.greenhouse.io/<token>";
            case LEVER -> "jobs.lever.co/<site>";
            case ASHBY -> "jobs.ashbyhq.com/<name>";
            case ADZUNA -> "none";
        };
    }
}
