package com.talentmatch.feed.source;

import static com.talentmatch.feed.source.SourceJson.decimal;
import static com.talentmatch.feed.source.SourceJson.httpUrl;
import static com.talentmatch.feed.source.SourceJson.instant;
import static com.talentmatch.feed.source.SourceJson.text;

import com.fasterxml.jackson.databind.JsonNode;
import com.talentmatch.feed.source.PostingFields.ParsedListing;
import com.talentmatch.feed.source.SourceFailure.Kind;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Greenhouse Job Board API (public, no auth), §4.2.1 as corrected by the probe findings.
 * <ul>
 *   <li>List {@code GET /v1/boards/{token}/jobs}: no content, so it stays small; complete listing.
 *       Company = the per-job {@code company_name}, else the source's name. {@code postedAt =
 *       first_published} (null when missing, never {@code updated_at}); {@code contentVersion =
 *       updated_at}; {@code descriptionComplete=false}.</li>
 *   <li>Detail {@code GET /v1/boards/{token}/jobs/{id}?pay_transparency=true}: the entity-escaped
 *       {@code content} is unescaped once to HTML; salary from the first {@code pay_input_ranges}
 *       entry (cents / 100, {@code currency_type}, period unknown so unset); country from
 *       {@code location.name} (may hold several ';'-separated locations) or {@code offices[]}
 *       (may be empty).</li>
 *   <li>Probe {@code GET /v1/boards/{token}} → {@code name}; its {@code content} is plain HTML and
 *       is not used (never unescaped).</li>
 *   <li>Conditional requests: ETag only (Greenhouse sends no {@code Last-Modified}).</li>
 * </ul>
 */
@Component
public class GreenhouseAdapter implements SourceAdapter {

    private static final BigDecimal CENTS = BigDecimal.valueOf(100);

    private final SourceHttpClient http;
    private final SourceProperties properties;

    public GreenhouseAdapter(SourceHttpClient http, SourceProperties properties) {
        this.http = http;
        this.properties = properties;
    }

    @Override
    public SourceKind kind() {
        return SourceKind.GREENHOUSE;
    }

    @Override
    public FetchResult fetch(SourceTarget target, FetchRequest request) {
        String token = SourceJson.requireToken(target.boardToken());
        URI uri = uri("/v1/boards/" + token + "/jobs");
        SourceResponse response = http.get(uri, request);
        if (response.notModified()) {
            return FetchResult.notModified(response, kind().completeListing());
        }
        return parseListing(response.body(), target, SourceHttpClient.describe(uri))
                .toResult(response, kind().completeListing());
    }

    @Override
    public Optional<BoardInfo> probe(String boardToken, Map<String, String> options) {
        String token = SourceJson.requireToken(boardToken);
        URI uri = uri("/v1/boards/" + token);
        SourceResponse response;
        try {
            response = http.get(uri, FetchRequest.NONE);
        } catch (SourceException e) {
            if (e.kind() == Kind.NOT_FOUND) {
                return Optional.empty();
            }
            throw e;
        }
        JsonNode root = SourceJson.parse(response.body(), SourceHttpClient.describe(uri));
        if (!root.isObject()) {
            throw SourceJson.invalid("Expected a board object from " + SourceHttpClient.describe(uri));
        }
        return Optional.of(new BoardInfo(text(root, "name"), null, null));
    }

    @Override
    public Optional<RawPosting> fetchDetail(SourceTarget target, String externalId) {
        String token = SourceJson.requireToken(target.boardToken());
        String id = SourceJson.requireToken(externalId);
        URI uri = uri("/v1/boards/" + token + "/jobs/" + id + "?pay_transparency=true");
        SourceResponse response;
        try {
            response = http.get(uri, FetchRequest.NONE);
        } catch (SourceException e) {
            if (e.kind() == Kind.NOT_FOUND) {
                return Optional.empty();            // closed between the list and the detail call
            }
            throw e;
        }
        return Optional.of(parseDetail(response.body(), target, SourceHttpClient.describe(uri)));
    }

    /** Maps a list body ({@code {"jobs":[…],"meta":{…}}}). */
    ParsedListing parseListing(byte[] body, SourceTarget target, String where) {
        JsonNode root = SourceJson.parse(body, where);
        JsonNode jobs = SourceJson.requireArray(root.get("jobs"), "jobs", properties.maxPostingsPerSource(), where);
        return PostingFields.mapAll(kind(), jobs, job -> true, job -> mapListJob(job, target));
    }

    /** Maps a detail body; INVALID_RESPONSE when it has no id, title or http(s) URL. */
    RawPosting parseDetail(byte[] body, SourceTarget target, String where) {
        JsonNode job = SourceJson.parse(body, where);
        RawPosting posting = job.isObject() ? mapDetailJob(job, target) : null;
        if (posting == null) {
            throw SourceJson.invalid("Posting detail without id, title or http(s) URL from " + where);
        }
        return posting;
    }

    private static RawPosting mapListJob(JsonNode job, SourceTarget target) {
        String id = text(job, "id");
        String title = text(job, "title");
        String url = httpUrl(text(job, "absolute_url"));
        if (id == null || title == null || url == null) {
            return null;
        }
        String location = text(job.path("location"), "name");
        Instant updatedAt = instant(job.get("updated_at"));
        return new RawPosting(id, url, title, target.company(text(job, "company_name")),
                null, true, false,
                location, PostingFields.countryFromLocation(location), PostingFields.workplaceFromText(location), null,
                null, null, null, null, false,
                instant(job.get("first_published")), updatedAt, updatedAt);
    }

    private static RawPosting mapDetailJob(JsonNode job, SourceTarget target) {
        String id = text(job, "id");
        String title = text(job, "title");
        String url = httpUrl(text(job, "absolute_url"));
        if (id == null || title == null || url == null) {
            return null;
        }
        String location = text(job.path("location"), "name");
        String country = PostingFields.countryFromLocation(location);
        if (country == null) {
            country = countryFromOffices(job.path("offices"));
        }
        String content = text(job, "content");
        String html = content == null ? null : HtmlText.unescape(content);

        BigDecimal salaryMin = null;
        BigDecimal salaryMax = null;
        String currency = null;
        JsonNode range = firstPayRange(job.path("pay_input_ranges"));
        if (range != null) {
            salaryMin = cents(range.get("min_cents"));
            salaryMax = cents(range.get("max_cents"));
            if (salaryMin != null && salaryMax != null && salaryMin.compareTo(salaryMax) > 0) {
                BigDecimal swap = salaryMin;
                salaryMin = salaryMax;
                salaryMax = swap;
            }
            currency = salaryMin == null && salaryMax == null ? null : PostingFields.currency(text(range, "currency_type"));
        }
        Instant updatedAt = instant(job.get("updated_at"));
        return new RawPosting(id, url, title, target.company(text(job, "company_name")),
                html, true, html != null,
                location, country, PostingFields.workplaceFromText(location), null,
                salaryMin, salaryMax, currency, null, false,
                instant(job.get("first_published")), updatedAt, updatedAt);
    }

    /** The first range with an amount; several regional ranges may exist, [] means no salary. */
    private static JsonNode firstPayRange(JsonNode ranges) {
        if (ranges == null || !ranges.isArray()) {
            return null;
        }
        for (JsonNode range : ranges) {
            if (decimal(range.get("min_cents")) != null || decimal(range.get("max_cents")) != null) {
                return range;
            }
        }
        return null;
    }

    private static BigDecimal cents(JsonNode node) {
        BigDecimal value = decimal(node);
        if (value == null || value.signum() < 0) {
            return null;
        }
        return value.divide(CENTS).setScale(2, RoundingMode.HALF_UP);
    }

    /** {@code offices[].location} is a string or {@code {"name":…}}; the office name is the last resort. */
    private static String countryFromOffices(JsonNode offices) {
        if (offices == null || !offices.isArray()) {
            return null;
        }
        for (JsonNode office : offices) {
            JsonNode location = office.get("location");
            String text = location != null && location.isObject() ? text(location, "name") : text(location);
            String code = PostingFields.countryFromLocation(text);
            if (code == null) {
                code = PostingFields.countryFromLocation(text(office, "name"));
            }
            if (code != null) {
                return code;
            }
        }
        return null;
    }

    private URI uri(String pathAndQuery) {
        return URI.create(properties.greenhouse().baseUrl() + pathAndQuery);
    }
}
