package com.talentmatch.feed.source;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.talentmatch.preferences.SalaryPeriod;
import com.talentmatch.preferences.Workplace;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** §9.1 item 1 (Adzuna, §4.2.4): mapping, the search URL (keys never logged), probe and failures. */
class AdzunaAdapterTest {

    private static final String WHERE = "api.adzuna.com/v1/api/jobs/za/search/1";
    private static final String KEY = "SECRET-KEY-123";
    private final SourceTarget target = new SourceTarget(null, null, Map.of("country", "za", "what", "java"));

    private static SourceProperties props(URI base) {
        return new SourceProperties(5000, Fixtures.http(Duration.ofSeconds(2), Duration.ofSeconds(2), 20_971_520L,
                Duration.ZERO), null, null, null,
                new SourceProperties.Adzuna(base, "app-1", KEY, "za", 50, 2, 200, 3, Duration.ofDays(14)));
    }

    private final SourceProperties props = props(URI.create("https://api.adzuna.com"));
    private final AdzunaAdapter adapter = new AdzunaAdapter(Fixtures.client(props), props);

    private List<RawPosting> postings() {
        PostingFields.ParsedListing parsed = adapter.parseListing(Fixtures.bytes("adzuna.json"), target, WHERE);
        assertThat(parsed.skipped()).isZero();
        return parsed.postings();
    }

    private RawPosting byId(String id) {
        return postings().stream().filter(p -> p.externalId().equals(id)).findFirst().orElseThrow();
    }

    @Test
    void mapsResultsAndLeavesOutResultsWithoutCompany() {
        assertThat(postings()).extracting(RawPosting::externalId)
                .containsExactly("4877291034", "4877288812", "4877260077");
        RawPosting p = byId("4877291034");
        assertThat(p.title()).isEqualTo("Senior Java Developer");               // highlight tags removed
        assertThat(p.company()).isEqualTo("Acme Payments (Pty) Ltd");
        assertThat(p.url()).startsWith("https://www.adzuna.co.za/land/ad/4877291034");
        assertThat(p.locationText()).isEqualTo("Cape Town, Western Cape");
        assertThat(p.countryCode()).isEqualTo("ZA");
        assertThat(p.postedAt()).isEqualTo(Instant.parse("2026-10-11T08:12:33Z"));
        assertThat(p.employmentType()).isEqualTo("Full-time, Permanent");
        assertThat(p.workplace()).isEqualTo(Workplace.UNKNOWN);
        assertThat(p.contentVersion()).isNull();
        assertThat(p.sourceUpdatedAt()).isNull();
    }

    @Test
    void descriptionIsAnHtmlSnippet() {
        RawPosting p = byId("4877291034");
        assertThat(p.descriptionIsHtml()).isTrue();
        assertThat(p.descriptionComplete()).isFalse();
        assertThat(p.descriptionHtmlOrText()).contains("Spring Boot services");
    }

    @Test
    void salaryIsYearlyInRandAndPredictedMeansEstimated() {
        RawPosting real = byId("4877291034");
        assertThat(real.salaryMin()).isEqualByComparingTo(new BigDecimal("720000"));
        assertThat(real.salaryMax()).isEqualByComparingTo(new BigDecimal("900000"));
        assertThat(real.salaryCurrency()).isEqualTo("ZAR");
        assertThat(real.salaryPeriod()).isEqualTo(SalaryPeriod.YEAR);
        assertThat(real.salaryEstimated()).isFalse();

        RawPosting predicted = byId("4877288812");
        assertThat(predicted.salaryEstimated()).isTrue();

        RawPosting none = byId("4877260077");
        assertThat(none.salaryMin()).isNull();
        assertThat(none.salaryCurrency()).isNull();
        assertThat(none.salaryPeriod()).isNull();
        assertThat(none.salaryEstimated()).isFalse();
        assertThat(none.employmentType()).isEqualTo("Contract");
    }

    @Test
    void workplaceFromLocationOrTitle() {
        assertThat(byId("4877288812").workplace()).isEqualTo(Workplace.REMOTE);       // "(Remote)" in the title
    }

    @Test
    void searchUriCarriesKeysTermsAndPagingButDescribeHidesThem() {
        URI uri = adapter.searchUri(Map.of("country", "za", "what", "java developer", "where", "Cape Town"), 2, 50);
        assertThat(uri.getPath()).isEqualTo("/v1/api/jobs/za/search/2");
        assertThat(uri.getRawQuery()).contains("app_id=app-1", "app_key=" + KEY, "results_per_page=50",
                "what=java+developer", "where=Cape+Town", "max_days_old=2", "sort_by=date");
        assertThat(SourceHttpClient.describe(uri)).isEqualTo(WHERE.replace("/1", "/2")).doesNotContain(KEY);
        assertThat(adapter.searchUri(Map.of("country", "za"), 1, 1).getRawQuery()).doesNotContain("what=", "where=");
    }

    @Test
    void notConfiguredOrBadCountryRefusesToBuildAUrl() {
        SourceProperties none = SourceProperties.defaults();
        AdzunaAdapter unconfigured = new AdzunaAdapter(Fixtures.client(none), none);
        assertThrows(IllegalStateException.class, () -> unconfigured.searchUri(Map.of("country", "za"), 1, 50));
        assertThrows(IllegalArgumentException.class, () -> adapter.searchUri(Map.of("country", "../x"), 1, 50));
    }

    @Test
    void fetchAndPage2AgainstAStub() throws Exception {
        try (FeedStubServer stub = new FeedStubServer()) {
            SourceProperties p = props(stub.baseUrl());
            AdzunaAdapter a = new AdzunaAdapter(Fixtures.client(p), p);
            stub.handler(FeedStubServer.bytes(200, "application/json", Fixtures.bytes("adzuna.json"), Map.of()));

            FetchResult first = a.fetch(target, FetchRequest.NONE);
            assertThat(first.complete()).isFalse();
            assertThat(first.postings()).hasSize(3);
            assertThat(stub.last().uri().getPath()).isEqualTo("/v1/api/jobs/za/search/1");

            FetchResult again = a.fetch(target, new FetchRequest(null, null, first.bodyHash()));
            assertThat(again.notModified()).as("identical body").isTrue();

            Optional<FetchResult> page2 = a.fetchPage(target, 2);
            assertThat(page2).isPresent();
            assertThat(stub.last().uri().getPath()).isEqualTo("/v1/api/jobs/za/search/2");
        }
    }

    @Test
    void unauthorizedKeysAreUnauthorizedWithoutTheKeyInTheMessage() throws Exception {
        try (FeedStubServer stub = new FeedStubServer()) {
            SourceProperties p = props(stub.baseUrl());
            AdzunaAdapter a = new AdzunaAdapter(Fixtures.client(p), p);
            stub.handler(FeedStubServer.json(401,
                    "{\"exception\":\"AUTH_FAIL\",\"display\":\"Authorisation failed\"}"));
            SourceException e = assertThrows(SourceException.class, () -> a.fetch(target, FetchRequest.NONE));
            assertThat(e.kind()).isEqualTo(SourceFailure.Kind.UNAUTHORIZED);
            assertThat(e.getMessage()).doesNotContain(KEY).doesNotContain("app_key");
            SourceException probe = assertThrows(SourceException.class, () -> a.probe(null, target.options()));
            assertThat(probe.kind()).isEqualTo(SourceFailure.Kind.UNAUTHORIZED);
        }
    }

    @Test
    void probeRunsTheSearchForOneResult() throws Exception {
        try (FeedStubServer stub = new FeedStubServer()) {
            SourceProperties p = props(stub.baseUrl());
            AdzunaAdapter a = new AdzunaAdapter(Fixtures.client(p), p);
            stub.handler(FeedStubServer.bytes(200, "application/json", Fixtures.bytes("adzuna.json"), Map.of()));
            BoardInfo found = a.probe(null, target.options()).orElseThrow();
            assertThat(found.openPostings()).isEqualTo(137);
            assertThat(found.warning()).isNull();
            assertThat(stub.last().uri().getRawQuery()).contains("results_per_page=1");

            stub.handler(FeedStubServer.json(200, "{\"count\":0,\"results\":[]}"));
            BoardInfo empty = a.probe(null, target.options()).orElseThrow();
            assertThat(empty.openPostings()).isZero();
            assertThat(empty.warning()).isEqualTo(AdzunaAdapter.NO_RESULTS);
        }
    }

    @Test
    void propertiesMaskTheKey() {
        assertThat(props.adzuna().toString()).doesNotContain(KEY).doesNotContain("app-1").contains("****");
        assertThat(props.adzuna().configured()).isTrue();
        assertThat(SourceProperties.defaults().adzuna().configured()).isFalse();
    }
}
