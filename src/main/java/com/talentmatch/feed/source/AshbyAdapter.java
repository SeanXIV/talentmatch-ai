package com.talentmatch.feed.source;

import static com.talentmatch.feed.source.SourceJson.bool;
import static com.talentmatch.feed.source.SourceJson.decimal;
import static com.talentmatch.feed.source.SourceJson.httpUrl;
import static com.talentmatch.feed.source.SourceJson.instant;
import static com.talentmatch.feed.source.SourceJson.text;

import com.fasterxml.jackson.databind.JsonNode;
import com.talentmatch.feed.source.PostingFields.ParsedListing;
import com.talentmatch.feed.source.SourceFailure.Kind;
import com.talentmatch.preferences.SalaryPeriod;
import com.talentmatch.preferences.Workplace;
import java.math.BigDecimal;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Ashby public posting API, §4.2.3 as corrected by the probe findings.
 * <ul>
 *   <li>{@code GET {base}/posting-api/job-board/{name}?includeCompensation=true} →
 *       {@code {"apiVersion":"1","jobs":[…]}}; complete listing. {@code isListed=false} postings are
 *       left out (not counted as skipped).</li>
 *   <li>Title trimmed (it can have leading spaces). Description = {@code descriptionPlain}, else
 *       {@code descriptionHtml}. {@code postedAt = publishedAt}; no update timestamp, so
 *       {@code contentVersion} is null.</li>
 *   <li>Workplace: {@code workplaceType} {@code Remote|Hybrid|OnSite}, any case; when it is null (or
 *       unknown), {@code isRemote == true} → REMOTE, otherwise UNKNOWN.</li>
 *   <li>Country: {@code address.postalAddress.addressCountry} is free text ("USA", "United States",
 *       "South Korea"); regions such as "European Union", or a missing value → null.</li>
 *   <li>Salary: {@code compensation.summaryComponents} (else the tiers' components) with
 *       {@code compensationType == "Salary"}, a known interval ({@code 1 YEAR|1 MONTH|1 HOUR}; NONE
 *       ignored) and a non-null {@code minValue}. Entries sharing the first one's period and
 *       currency are merged: min of the mins, max of the maxes.</li>
 *   <li>Probe: the same URL without compensation. Unknown boards answer {@code 404 text/plain}; the
 *       status is checked before any parsing. 200 with no listed postings → a warning.</li>
 * </ul>
 */
@Component
public class AshbyAdapter implements SourceAdapter {

    private final SourceHttpClient http;
    private final SourceProperties properties;

    public AshbyAdapter(SourceHttpClient http, SourceProperties properties) {
        this.http = http;
        this.properties = properties;
    }

    @Override
    public SourceKind kind() {
        return SourceKind.ASHBY;
    }

    @Override
    public FetchResult fetch(SourceTarget target, FetchRequest request) {
        URI uri = uri(target.boardToken(), "?includeCompensation=true");
        SourceResponse response = http.get(uri, request);
        if (response.notModified()) {
            return FetchResult.notModified(response, kind().completeListing());
        }
        return parseListing(response.body(), target, SourceHttpClient.describe(uri))
                .toResult(response, kind().completeListing());
    }

    @Override
    public Optional<BoardInfo> probe(String boardToken, Map<String, String> options) {
        URI uri = uri(boardToken, "");
        SourceResponse response;
        try {
            response = http.get(uri, FetchRequest.NONE);
        } catch (SourceException e) {
            if (e.kind() == Kind.NOT_FOUND) {
                return Optional.empty();
            }
            throw e;
        }
        String where = SourceHttpClient.describe(uri);
        JsonNode jobs = jobsArray(SourceJson.parse(response.body(), where), where);
        int listed = 0;
        for (JsonNode job : jobs) {
            if (isListed(job)) {
                listed++;
            }
        }
        return Optional.of(listed == 0
                ? new BoardInfo(null, 0, BoardInfo.NO_OPEN_POSTINGS)
                : new BoardInfo(null, listed, null));
    }

    /** Maps a listing body ({@code {"jobs":[…]}}). */
    ParsedListing parseListing(byte[] body, SourceTarget target, String where) {
        JsonNode jobs = jobsArray(SourceJson.parse(body, where), where);
        return PostingFields.mapAll(kind(), jobs, AshbyAdapter::isListed, job -> map(job, target));
    }

    private JsonNode jobsArray(JsonNode root, String where) {
        if (!root.isObject()) {
            throw SourceJson.invalid("Expected a job board object from " + where);
        }
        return SourceJson.requireArray(root.get("jobs"), "jobs", properties.maxPostingsPerSource(), where);
    }

    /** Only an explicit {@code isListed: false} hides a posting. */
    private static boolean isListed(JsonNode job) {
        return !Boolean.FALSE.equals(bool(job.get("isListed")));
    }

    private static RawPosting map(JsonNode job, SourceTarget target) {
        String id = text(job, "id");
        String title = text(job, "title");                  // text() strips: " Security Engineer" → trimmed
        String url = httpUrl(text(job, "jobUrl"));
        if (id == null || title == null || url == null) {
            return null;
        }
        List<String> locations = new ArrayList<>();
        locations.add(text(job, "location"));
        JsonNode secondary = job.path("secondaryLocations");
        if (secondary.isArray()) {
            secondary.forEach(s -> locations.add(text(s, "location")));
        }
        String country = CountryNames.toIso2(text(job.path("address").path("postalAddress"), "addressCountry"));

        String plain = text(job, "descriptionPlain");
        String html = plain == null ? text(job, "descriptionHtml") : null;
        String description = plain != null ? plain : html;

        Salary salary = salary(job.path("compensation"));
        return new RawPosting(id, url, title, target.company(null),
                description, plain == null && html != null, description != null,
                PostingFields.joinLocations(locations), country,
                workplace(text(job, "workplaceType"), bool(job.get("isRemote"))), text(job, "employmentType"),
                salary == null ? null : salary.min, salary == null ? null : salary.max,
                salary == null ? null : salary.currency, salary == null ? null : salary.period, false,
                instant(job.get("publishedAt")), null, null);
    }

    static Workplace workplace(String workplaceType, Boolean isRemote) {
        Workplace mapped = PostingFields.workplace(workplaceType);
        if (mapped != null) {
            return mapped;
        }
        return Boolean.TRUE.equals(isRemote) ? Workplace.REMOTE : Workplace.UNKNOWN;
    }

    /** The merged salary range, or null when the posting has none. */
    static Salary salary(JsonNode compensation) {
        if (compensation == null || !compensation.isObject()) {
            return null;
        }
        List<JsonNode> components = new ArrayList<>();
        JsonNode summary = compensation.path("summaryComponents");
        if (summary.isArray()) {
            summary.forEach(components::add);
        }
        if (components.isEmpty()) {
            JsonNode tiers = compensation.path("compensationTiers");
            if (tiers.isArray()) {
                for (JsonNode tier : tiers) {
                    JsonNode tierComponents = tier.path("components");
                    if (tierComponents.isArray()) {
                        tierComponents.forEach(components::add);
                    }
                }
            }
        }
        Salary merged = null;
        for (JsonNode component : components) {
            if (!"Salary".equalsIgnoreCase(text(component, "compensationType"))) {
                continue;
            }
            SalaryPeriod period = ashbyPeriod(text(component, "interval"));
            BigDecimal min = nonNegative(decimal(component.get("minValue")));
            if (period == null || min == null) {
                continue;
            }
            BigDecimal max = nonNegative(decimal(component.get("maxValue")));
            String currency = PostingFields.currency(text(component, "currencyCode"));
            if (merged == null) {
                merged = new Salary(min, max, currency, period);
            } else if (merged.period == period && Objects.equals(merged.currency, currency)) {
                merged = merged.widen(min, max);
            }
        }
        if (merged != null && merged.max != null && merged.min.compareTo(merged.max) > 0) {
            merged = new Salary(merged.max, merged.min, merged.currency, merged.period);
        }
        return merged;
    }

    /** "1 YEAR" / "1 MONTH" / "1 HOUR" (and "1 DAY"); "NONE", other counts or units → null. */
    private static SalaryPeriod ashbyPeriod(String interval) {
        if (interval == null) {
            return null;
        }
        String[] parts = interval.strip().split("\\s+");
        if (parts.length != 2 || !parts[0].equals("1")) {
            return null;
        }
        switch (parts[1].toUpperCase(java.util.Locale.ROOT)) {
            case "YEAR":
                return SalaryPeriod.YEAR;
            case "MONTH":
                return SalaryPeriod.MONTH;
            case "HOUR":
                return SalaryPeriod.HOUR;
            case "DAY":
                return SalaryPeriod.DAY;
            default:
                return null;
        }
    }

    private static BigDecimal nonNegative(BigDecimal value) {
        return value == null || value.signum() < 0 ? null : value;
    }

    private URI uri(String boardToken, String query) {
        String token = SourceJson.requireToken(boardToken);
        return URI.create(properties.ashby().baseUrl() + "/posting-api/job-board/" + token + query);
    }

    /** A salary range; {@code max} is null when no entry had one. */
    record Salary(BigDecimal min, BigDecimal max, String currency, SalaryPeriod period) {

        Salary widen(BigDecimal otherMin, BigDecimal otherMax) {
            BigDecimal newMin = otherMin.compareTo(min) < 0 ? otherMin : min;
            BigDecimal newMax = max;
            if (otherMax != null && (newMax == null || otherMax.compareTo(newMax) > 0)) {
                newMax = otherMax;
            }
            return new Salary(newMin, newMax, currency, period);
        }
    }
}
