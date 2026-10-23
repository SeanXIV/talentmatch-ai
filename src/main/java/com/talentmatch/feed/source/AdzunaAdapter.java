package com.talentmatch.feed.source;

import static com.talentmatch.feed.source.SourceJson.decimal;
import static com.talentmatch.feed.source.SourceJson.httpUrl;
import static com.talentmatch.feed.source.SourceJson.instant;
import static com.talentmatch.feed.source.SourceJson.text;

import com.fasterxml.jackson.databind.JsonNode;
import com.talentmatch.feed.source.PostingFields.ParsedListing;
import com.talentmatch.preferences.SalaryPeriod;
import com.talentmatch.preferences.Workplace;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Adzuna search API (§4.2.4, decision c). One source is one saved search ({@code options.country},
 * {@code options.what}, {@code options.where}); it has no board token.
 * <ul>
 *   <li>{@code GET {base}/v1/api/jobs/{country}/search/{page}?app_id&app_key&results_per_page&what&where
 *       &max_days_old&sort_by=date}: newest first. The keys travel in the query, so URIs are only ever
 *       logged through {@link SourceHttpClient#describe} (host and path).</li>
 *   <li>A search result is never a complete listing ({@link SourceKind#completeListing()} false):
 *       a posting missing from it is not closed (the poller closes Adzuna postings left unseen for
 *       {@code unseen-close-after}).</li>
 *   <li>The description is a snippet with highlight tags ({@code descriptionComplete=false}, HTML).
 *       Results without a company name are left out (they can't be deduplicated).</li>
 *   <li>Salaries are yearly, in the country's currency (Adzuna sends none, so it comes from
 *       {@link #CURRENCIES}); {@code salary_is_predicted = "1"} marks an estimate, never used for
 *       filtering.</li>
 *   <li>Wrong keys answer {@code 401 AUTH_FAIL} → UNAUTHORIZED (checked against the live API).</li>
 * </ul>
 * Field names follow the public API documentation; no keys were available for a live probe, so
 * {@code tests/resources/feed/adzuna.json} is modelled on the documented response.
 */
@Component
public class AdzunaAdapter implements SourceAdapter {

    /** Adzuna country → ISO 4217 currency of its salaries. */
    static final Map<String, String> CURRENCIES = Map.ofEntries(
            Map.entry("za", "ZAR"), Map.entry("gb", "GBP"), Map.entry("us", "USD"), Map.entry("ca", "CAD"),
            Map.entry("au", "AUD"), Map.entry("nz", "NZD"), Map.entry("in", "INR"), Map.entry("sg", "SGD"),
            Map.entry("br", "BRL"), Map.entry("mx", "MXN"), Map.entry("pl", "PLN"), Map.entry("ch", "CHF"),
            Map.entry("de", "EUR"), Map.entry("fr", "EUR"), Map.entry("nl", "EUR"), Map.entry("it", "EUR"),
            Map.entry("es", "EUR"), Map.entry("at", "EUR"), Map.entry("be", "EUR"));

    private final SourceHttpClient http;
    private final SourceProperties properties;

    public AdzunaAdapter(SourceHttpClient http, SourceProperties properties) {
        this.http = http;
        this.properties = properties;
    }

    @Override
    public SourceKind kind() {
        return SourceKind.ADZUNA;
    }

    @Override
    public FetchResult fetch(SourceTarget target, FetchRequest request) {
        URI uri = searchUri(target.options(), 1, properties.adzuna().resultsPerPage());
        SourceResponse response = http.get(uri, request);
        if (response.notModified()) {
            return FetchResult.notModified(response, kind().completeListing());
        }
        return parseListing(response.body(), target, SourceHttpClient.describe(uri))
                .toResult(response, kind().completeListing());
    }

    @Override
    public Optional<FetchResult> fetchPage(SourceTarget target, int page) {
        if (page < 2) {
            throw new IllegalArgumentException("page must be 2 or more");
        }
        URI uri = searchUri(target.options(), page, properties.adzuna().resultsPerPage());
        SourceResponse response = http.get(uri, FetchRequest.NONE);
        return Optional.of(parseListing(response.body(), target, SourceHttpClient.describe(uri))
                .toResult(response, kind().completeListing()));
    }

    /**
     * Runs the search for one result. A search always "exists": empty means nothing is found right
     * now (a warning), never NOT_FOUND.
     */
    @Override
    public Optional<BoardInfo> probe(String boardToken, Map<String, String> options) {
        URI uri = searchUri(options, 1, 1);
        SourceResponse response = http.get(uri, FetchRequest.NONE);
        JsonNode root = SourceJson.parse(response.body(), SourceHttpClient.describe(uri));
        JsonNode count = root.get("count");
        Integer found = count != null && count.canConvertToInt() ? count.intValue() : null;
        boolean none = found != null ? found == 0 : root.path("results").isEmpty();
        return Optional.of(new BoardInfo(null, found, none ? NO_RESULTS : null));
    }

    static final String NO_RESULTS = "The Adzuna search finds no jobs right now; it keeps checking for new ones.";

    /** Maps a search body ({@code {"count", "results": [...]}}). */
    ParsedListing parseListing(byte[] body, SourceTarget target, String where) {
        JsonNode root = SourceJson.parse(body, where);
        JsonNode results = SourceJson.requireArray(root.get("results"), "results", properties.maxPostingsPerSource(),
                where);
        String country = country(target.options());
        return PostingFields.mapAll(kind(), results,
                result -> text(result.path("company"), "display_name") != null,
                result -> map(result, country));
    }

    private static RawPosting map(JsonNode result, String country) {
        String id = text(result, "id");
        String title = title(text(result, "title"));
        String url = httpUrl(text(result, "redirect_url"));
        if (id == null || title == null || url == null) {
            return null;
        }
        String location = text(result.path("location"), "display_name");
        Workplace workplace = PostingFields.workplaceFromText(location);
        if (workplace == Workplace.UNKNOWN) {
            workplace = PostingFields.workplaceFromText(title);
        }

        BigDecimal salaryMin = nonNegative(decimal(result.get("salary_min")));
        BigDecimal salaryMax = nonNegative(decimal(result.get("salary_max")));
        boolean hasSalary = salaryMin != null || salaryMax != null;
        boolean estimated = hasSalary && "1".equals(text(result, "salary_is_predicted"));

        return new RawPosting(id, url, title, text(result.path("company"), "display_name"),
                text(result, "description"), true, false,
                location, country.toUpperCase(Locale.ROOT), workplace, employmentType(result),
                salaryMin, salaryMax, hasSalary ? CURRENCIES.get(country) : null,
                hasSalary ? SalaryPeriod.YEAR : null, estimated,
                instant(result.get("created")), null, null);
    }

    /** Titles may carry highlight tags ({@code <strong>Java</strong> Developer}). */
    private static String title(String raw) {
        if (raw == null) {
            return null;
        }
        String text = raw.indexOf('<') >= 0 || raw.indexOf('&') >= 0 ? HtmlText.toText(raw).strip() : raw;
        return text.isEmpty() ? null : text;
    }

    /** {@code contract_time} (full_time, part_time) and {@code contract_type} (permanent, contract). */
    private static String employmentType(JsonNode result) {
        List<String> parts = new ArrayList<>(2);
        for (String field : List.of("contract_time", "contract_type")) {
            String value = text(result, field);
            if (value != null) {
                String words = value.replace('_', '-').toLowerCase(Locale.ROOT);
                parts.add(Character.toUpperCase(words.charAt(0)) + words.substring(1));
            }
        }
        return parts.isEmpty() ? null : String.join(", ", parts);
    }

    private static BigDecimal nonNegative(BigDecimal value) {
        return value == null || value.signum() < 0 ? null : value;
    }

    private String country(Map<String, String> options) {
        String c = options == null ? null : options.get("country");
        c = c == null || c.isBlank() ? properties.adzuna().country() : c.strip().toLowerCase(Locale.ROOT);
        if (!c.matches("^[a-z]{2}$")) {
            throw new IllegalArgumentException("An Adzuna source needs options.country, a two-letter country code");
        }
        return c;
    }

    /**
     * The search URI. Keys and terms are form-encoded; the country is validated.
     *
     * @throws IllegalStateException when the keys are not configured (the poller checks first)
     */
    URI searchUri(Map<String, String> options, int page, int resultsPerPage) {
        SourceProperties.Adzuna adzuna = properties.adzuna();
        if (!adzuna.configured()) {
            throw new IllegalStateException("Adzuna is not configured (ADZUNA_APP_ID / ADZUNA_APP_KEY)");
        }
        String country = country(options);
        StringBuilder query = new StringBuilder()
                .append("app_id=").append(encode(adzuna.appId()))
                .append("&app_key=").append(encode(adzuna.appKey()))
                .append("&results_per_page=").append(resultsPerPage);
        String what = options == null ? null : options.get("what");
        if (what != null && !what.isBlank()) {
            query.append("&what=").append(encode(what.strip()));
        }
        String where = options == null ? null : options.get("where");
        if (where != null && !where.isBlank()) {
            query.append("&where=").append(encode(where.strip()));
        }
        query.append("&max_days_old=").append(adzuna.maxDaysOld())
                .append("&sort_by=date&content-type=application/json");
        return URI.create(adzuna.baseUrl() + "/v1/api/jobs/" + country + "/search/" + page + "?" + query);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
