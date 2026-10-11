package com.talentmatch.feed.source;

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
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Lever Postings API v0 (public), §4.2.2 as corrected by the probe findings.
 * <ul>
 *   <li>{@code GET {base}/v0/postings/{site}?mode=json} returns every posting (a JSON array; no
 *       pagination needed). EU sites use {@code eu-base-url} ({@code options.leverInstance=eu}).
 *       Site tokens are case-sensitive and used as given.</li>
 *   <li>Description = {@code descriptionPlain} (+ {@code descriptionBodyPlain} when not already in
 *       it) + each list ({@code text} heading, then its HTML {@code content} as text) +
 *       {@code additionalPlain}; plain text.</li>
 *   <li>{@code country} (ISO-2) and {@code categories.commitment} are nullable; a missing country
 *       falls back to the location text. Salary interval {@code per-year-salary|per-month-salary|
 *       per-hour-wage} → YEAR/MONTH/HOUR. {@code postedAt = createdAt} (epoch ms).</li>
 *   <li>Probe: the same URL with {@code &limit=1}. 404 → no such site; {@code 200 []} → the site
 *       exists with no postings (a warning, not an error).</li>
 *   <li>Lever never gzips; the client's body cap applies to the plain stream.</li>
 * </ul>
 */
@Component
public class LeverAdapter implements SourceAdapter {

    /** {@code options} key choosing the instance: absent/"global" or "eu". */
    public static final String INSTANCE_OPTION = "leverInstance";

    private final SourceHttpClient http;
    private final SourceProperties properties;

    public LeverAdapter(SourceHttpClient http, SourceProperties properties) {
        this.http = http;
        this.properties = properties;
    }

    @Override
    public SourceKind kind() {
        return SourceKind.LEVER;
    }

    @Override
    public FetchResult fetch(SourceTarget target, FetchRequest request) {
        URI uri = uri(target.boardToken(), target.options(), "?mode=json");
        SourceResponse response = http.get(uri, request);
        if (response.notModified()) {
            return FetchResult.notModified(response, kind().completeListing());
        }
        return parseListing(response.body(), target, SourceHttpClient.describe(uri))
                .toResult(response, kind().completeListing());
    }

    @Override
    public Optional<BoardInfo> probe(String boardToken, Map<String, String> options) {
        URI uri = uri(boardToken, options, "?mode=json&limit=1");
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
        JsonNode postings = SourceJson.parse(response.body(), where);
        if (!postings.isArray()) {
            throw SourceJson.invalid("Expected an array of postings from " + where);
        }
        return Optional.of(postings.isEmpty()
                ? new BoardInfo(null, 0, BoardInfo.NO_OPEN_POSTINGS)
                : new BoardInfo(null, null, null));
    }

    /** Maps a listing body (a JSON array). */
    ParsedListing parseListing(byte[] body, SourceTarget target, String where) {
        JsonNode root = SourceJson.parse(body, where);
        JsonNode postings = SourceJson.requireArray(root, "the body", properties.maxPostingsPerSource(), where);
        return PostingFields.mapAll(kind(), postings, posting -> true, posting -> map(posting, target));
    }

    private static RawPosting map(JsonNode posting, SourceTarget target) {
        String id = text(posting, "id");
        String title = text(posting, "text");
        String url = httpUrl(text(posting, "hostedUrl"));
        if (id == null || title == null || url == null) {
            return null;
        }
        JsonNode categories = posting.path("categories");
        String location = location(categories);
        String country = CountryNames.toIso2(text(posting, "country"));
        if (country == null) {
            country = PostingFields.countryFromLocation(location);
        }
        Workplace workplace = PostingFields.workplace(text(posting, "workplaceType"));   // "unspecified" → null

        BigDecimal salaryMin = null;
        BigDecimal salaryMax = null;
        String currency = null;
        SalaryPeriod period = null;
        JsonNode salary = posting.get("salaryRange");
        if (salary != null && salary.isObject()) {
            salaryMin = nonNegative(decimal(salary.get("min")));
            salaryMax = nonNegative(decimal(salary.get("max")));
            if (salaryMin != null && salaryMax != null && salaryMin.compareTo(salaryMax) > 0) {
                BigDecimal swap = salaryMin;
                salaryMin = salaryMax;
                salaryMax = swap;
            }
            if (salaryMin != null || salaryMax != null) {
                currency = PostingFields.currency(text(salary, "currency"));
                period = PostingFields.period(text(salary, "interval"));
            }
        }
        return new RawPosting(id, url, title, target.company(null),
                description(posting), false, true,
                location, country, workplace, text(categories, "commitment"),
                salaryMin, salaryMax, currency, period, false,
                instant(posting.get("createdAt")), null, null);
    }

    /** {@code allLocations} when present (several cities), else {@code location}. */
    private static String location(JsonNode categories) {
        List<String> locations = new ArrayList<>();
        JsonNode all = categories.path("allLocations");
        if (all.isArray()) {
            all.forEach(node -> locations.add(text(node)));
        }
        if (locations.stream().allMatch(l -> l == null)) {
            locations.add(text(categories, "location"));
        }
        return PostingFields.joinLocations(locations);
    }

    static String description(JsonNode posting) {
        List<String> blocks = new ArrayList<>();
        String plain = text(posting, "descriptionPlain");
        if (plain != null) {
            blocks.add(plain);
        }
        String body = text(posting, "descriptionBodyPlain");
        if (body != null && (plain == null || !plain.contains(body))) {
            blocks.add(body);
        }
        JsonNode lists = posting.path("lists");
        if (lists.isArray()) {
            for (JsonNode list : lists) {
                String heading = text(list, "text");
                String content = HtmlText.toText(text(list, "content"));
                if (heading == null && content.isEmpty()) {
                    continue;
                }
                blocks.add(heading == null ? content : content.isEmpty() ? heading : heading + "\n" + content);
            }
        }
        String additional = text(posting, "additionalPlain");
        if (additional != null) {
            blocks.add(additional);
        }
        return blocks.isEmpty() ? null : String.join("\n\n", blocks);
    }

    private static BigDecimal nonNegative(BigDecimal value) {
        return value == null || value.signum() < 0 ? null : value;
    }

    private URI uri(String boardToken, Map<String, String> options, String query) {
        String token = SourceJson.requireToken(boardToken);
        return URI.create(baseUrl(options) + "/v0/postings/" + token + query);
    }

    private URI baseUrl(Map<String, String> options) {
        String instance = options == null ? null : options.get(INSTANCE_OPTION);
        if (instance == null || instance.isBlank() || instance.strip().equalsIgnoreCase("global")) {
            return properties.lever().baseUrl();
        }
        if (instance.strip().toLowerCase(Locale.ROOT).equals("eu")) {
            return properties.lever().euBaseUrl();
        }
        throw new IllegalArgumentException("options." + INSTANCE_OPTION + " must be \"eu\" or \"global\"");
    }
}
