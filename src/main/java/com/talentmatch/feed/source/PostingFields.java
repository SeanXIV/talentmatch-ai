package com.talentmatch.feed.source;

import com.fasterxml.jackson.databind.JsonNode;
import com.talentmatch.preferences.SalaryPeriod;
import com.talentmatch.preferences.Workplace;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Field mapping shared by the adapters (pure). */
final class PostingFields {

    private static final Logger log = LoggerFactory.getLogger(PostingFields.class);

    private static final Pattern CURRENCY = Pattern.compile("^[A-Z]{3}$");
    private static final Pattern REMOTE_WORD = Pattern.compile("(?iu)(?<![\\p{L}\\p{N}])remote(?![\\p{L}\\p{N}])");
    private static final Pattern HYBRID_WORD = Pattern.compile("(?iu)(?<![\\p{L}\\p{N}])hybrid(?![\\p{L}\\p{N}])");
    /** Work-mode words and brackets around a place: "Remote (US)", "Remote - Ireland", "Hybrid, Berlin". */
    private static final Pattern NOT_A_PLACE = Pattern.compile("(?iu)(?<![\\p{L}\\p{N}])(?:remote|hybrid|on-?site|"
            + "anywhere|in|office|hq)(?![\\p{L}\\p{N}])|[()\\[\\]–—/]|(?<=\\s)-(?=\\s)|^-|-$");
    private static final Pattern WORD_SEPARATORS = Pattern.compile("[\\s_-]+");
    /** Two-letter text counts as a country only for these; others are usually US states ("CA", "GA"). */
    private static final Set<String> TWO_LETTER_COUNTRIES = Set.of("US", "UK");
    private static final int MAX_LOGGED_ID = 80;

    private PostingFields() {
    }

    /** Postings mapped from one listing, plus the number that could not be mapped. */
    record ParsedListing(List<RawPosting> postings, int skipped) {

        ParsedListing {
            postings = List.copyOf(postings);
        }

        FetchResult toResult(SourceResponse response, boolean complete) {
            return new FetchResult(false, response.etag(), response.lastModified(), response.bodyHash(), complete,
                    postings, skipped);
        }
    }

    /**
     * Maps every item. Items failing {@code include} are left out silently (unlisted postings); a
     * null mapping or any exception counts as skipped, as does a repeated external id. Logs the
     * external id and the exception class only, never posting text.
     */
    static ParsedListing mapAll(SourceKind kind, JsonNode items, Predicate<JsonNode> include,
                                Function<JsonNode, RawPosting> mapper) {
        List<RawPosting> postings = new ArrayList<>(items.size());
        Set<String> seen = new HashSet<>();
        int skipped = 0;
        for (JsonNode item : items) {
            try {
                if (!include.test(item)) {
                    continue;
                }
                RawPosting posting = mapper.apply(item);
                if (posting == null) {
                    skipped++;
                    log.debug("Skipped {} posting id={}: no id, title or http(s) URL", kind, loggableId(item));
                } else if (!seen.add(posting.externalId())) {
                    skipped++;
                    log.debug("Skipped {} posting id={}: repeated id", kind, loggableId(item));
                } else {
                    postings.add(posting);
                }
            } catch (RuntimeException e) {
                skipped++;
                log.warn("Skipped {} posting id={}: {}", kind, loggableId(item), e.getClass().getSimpleName());
            }
        }
        return new ParsedListing(postings, skipped);
    }

    private static String loggableId(JsonNode item) {
        String id = item == null ? null : SourceJson.text(item, "id");
        if (id == null) {
            return "?";
        }
        String safe = id.codePoints()
                .filter(c -> c >= 0x20 && c != 0x7f)
                .limit(MAX_LOGGED_ID)
                .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append)
                .toString();
        return safe.isEmpty() ? "?" : safe;
    }

    /** "remote" / "Hybrid" / "OnSite" / "on-site" (any case) → the enum; anything else → null. */
    static Workplace workplace(String value) {
        if (value == null) {
            return null;
        }
        String key = WORD_SEPARATORS.matcher(value.strip().toLowerCase(Locale.ROOT)).replaceAll("");
        switch (key) {
            case "remote":
                return Workplace.REMOTE;
            case "hybrid":
                return Workplace.HYBRID;
            case "onsite":
            case "inoffice":
            case "office":
                return Workplace.ONSITE;
            default:
                return null;
        }
    }

    /** REMOTE/HYBRID when a free-text location says so ("Remote, United States"); else UNKNOWN. */
    static Workplace workplaceFromText(String location) {
        if (location == null) {
            return Workplace.UNKNOWN;
        }
        if (HYBRID_WORD.matcher(location).find()) {
            return Workplace.HYBRID;
        }
        return REMOTE_WORD.matcher(location).find() ? Workplace.REMOTE : Workplace.UNKNOWN;
    }

    /** An ISO 4217-shaped code in upper case, else null. */
    static String currency(String value) {
        if (value == null) {
            return null;
        }
        String code = value.strip().toUpperCase(Locale.ROOT);
        return CURRENCY.matcher(code).matches() ? code : null;
    }

    /** "per-year-salary", "yearly", "1 YEAR", "per-hour-wage", "1 MONTH" … → the period; else null. */
    static SalaryPeriod period(String value) {
        if (value == null) {
            return null;
        }
        String v = value.toLowerCase(Locale.ROOT);
        if (v.contains("year") || v.contains("annual")) {
            return SalaryPeriod.YEAR;
        }
        if (v.contains("month")) {
            return SalaryPeriod.MONTH;
        }
        if (v.contains("hour")) {
            return SalaryPeriod.HOUR;
        }
        if (v.contains("day") || v.contains("daily")) {
            return SalaryPeriod.DAY;
        }
        return null;
    }

    /**
     * The first country named in a free-text location. Several locations may be ';'-separated
     * ("Remote, Canada; Remote, United Kingdom"); within one, the comma-separated parts are tried
     * from the last ("Cape Town, South Africa"). Work-mode words and brackets are ignored
     * ("Remote Ireland", "Remote (US)"). Two-letter parts other than US/UK are ignored ("San
     * Francisco, CA" is California, not Canada).
     */
    static String countryFromLocation(String location) {
        if (location == null || location.isBlank()) {
            return null;
        }
        for (String place : location.split("[;|]")) {
            String[] parts = place.split(",");
            for (int i = parts.length - 1; i >= 0; i--) {
                String candidate = NOT_A_PLACE.matcher(parts[i]).replaceAll(" ").strip().replaceAll("\\s+", " ");
                if (candidate.isEmpty()) {
                    continue;
                }
                if (candidate.length() == 2 && !TWO_LETTER_COUNTRIES.contains(candidate)) {
                    continue;
                }
                String code = CountryNames.toIso2(candidate);
                if (code != null) {
                    return code;
                }
            }
        }
        return null;
    }

    /** Distinct non-blank locations joined with "; " (null when none). */
    static String joinLocations(Collection<String> locations) {
        Set<String> distinct = new LinkedHashSet<>();
        for (String location : locations) {
            if (location != null && !location.isBlank()) {
                distinct.add(location.strip());
            }
        }
        return distinct.isEmpty() ? null : String.join("; ", distinct);
    }
}
