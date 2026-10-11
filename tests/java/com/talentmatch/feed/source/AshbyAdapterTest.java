package com.talentmatch.feed.source;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.talentmatch.preferences.SalaryPeriod;
import com.talentmatch.preferences.Workplace;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** §9.1 item 1 (Ashby) with the probe-findings corrections to §4.2.3. */
class AshbyAdapterTest {

    private static final String WHERE = "api.ashbyhq.com/posting-api/job-board/ramp";
    private final SourceProperties props = Fixtures.props(5000);
    private final AshbyAdapter adapter = new AshbyAdapter(Fixtures.client(props), props);
    private final SourceTarget target = new SourceTarget("ramp", "Ramp");

    private PostingFields.ParsedListing parse(JsonNode root) {
        return adapter.parseListing(Fixtures.write(root), target, WHERE);
    }

    private List<RawPosting> postings() {
        return adapter.parseListing(Fixtures.bytes("ashby.json"), target, WHERE).postings();
    }

    private static ObjectNode job(JsonNode root, int i) {
        return (ObjectNode) root.get("jobs").get(i);
    }

    @Test
    void unlistedPostingExcludedAndNotCountedAsSkipped() {
        PostingFields.ParsedListing parsed = adapter.parseListing(Fixtures.bytes("ashby.json"), target, WHERE);
        assertThat(parsed.postings()).hasSize(3);
        assertThat(parsed.skipped()).isZero();
        assertThat(parsed.postings()).extracting(RawPosting::externalId)
                .doesNotContain("00000000-0000-4000-8000-000000000002");
    }

    @Test
    void titlesTrimmedAndFieldsMapped() {
        RawPosting p = postings().get(0);
        assertThat(p.title()).isEqualTo("Security Engineer, Cloud");
        assertThat(p.externalId()).isEqualTo("34413f8d-26bf-4bbc-8ade-eb309a0e2245");
        assertThat(p.url()).isEqualTo("https://jobs.ashbyhq.com/ramp/34413f8d-26bf-4bbc-8ade-eb309a0e2245");
        assertThat(p.company()).isEqualTo("Ramp");
        assertThat(p.employmentType()).isEqualTo("FullTime");
        assertThat(p.descriptionHtmlOrText()).startsWith("ABOUT RAMP");
        assertThat(p.descriptionIsHtml()).isFalse();
        assertThat(p.descriptionComplete()).isTrue();
        assertThat(p.postedAt()).isEqualTo(OffsetDateTime.parse("2026-04-07T17:12:35.753+00:00").toInstant());
        assertThat(p.locationText()).startsWith("New York, NY (HQ)").contains("Remote (US)");
        assertThat(postings()).allSatisfy(x -> {
            assertThat(x.title()).isEqualTo(x.title().strip());
            assertThat(x.contentVersion()).isNull();
        });
    }

    @Test
    void workplaceMapping() {
        assertThat(postings()).extracting(RawPosting::workplace)
                .containsExactly(Workplace.HYBRID, Workplace.REMOTE, Workplace.UNKNOWN);   // Hybrid beats isRemote=true
        assertThat(AshbyAdapter.workplace("OnSite", null)).isEqualTo(Workplace.ONSITE);
        assertThat(AshbyAdapter.workplace("remote", false)).isEqualTo(Workplace.REMOTE);
        assertThat(AshbyAdapter.workplace(null, true)).isEqualTo(Workplace.REMOTE);
        assertThat(AshbyAdapter.workplace(null, false)).isEqualTo(Workplace.UNKNOWN);
        assertThat(AshbyAdapter.workplace(null, null)).isEqualTo(Workplace.UNKNOWN);
    }

    @Test
    void workplaceNullWithIsRemoteTrueIsRemoteInListing() {
        JsonNode root = Fixtures.tree("ashby.json");
        job(root, 2).put("isRemote", true);
        assertThat(parse(root).postings().get(2).workplace()).isEqualTo(Workplace.REMOTE);
    }

    @Test
    void countries() {
        assertThat(postings()).extracting(RawPosting::countryCode).containsExactly("US", "US", "DE");
        JsonNode root = Fixtures.tree("ashby.json");
        ((ObjectNode) job(root, 0).get("address").get("postalAddress")).put("addressCountry", "European Union");
        job(root, 1).remove("address");
        List<RawPosting> parsed = parse(root).postings();
        assertThat(parsed.get(0).countryCode()).isNull();
        assertThat(parsed.get(1).countryCode()).isNull();
    }

    @Test
    void salaryYearlyFromSummaryComponentsIgnoringEquity() {
        List<RawPosting> ps = postings();
        assertThat(ps.get(0).salaryMin()).isEqualByComparingTo(new BigDecimal("211400"));
        assertThat(ps.get(0).salaryMax()).isEqualByComparingTo(new BigDecimal("290600"));
        assertThat(ps.get(0).salaryCurrency()).isEqualTo("USD");
        assertThat(ps.get(0).salaryPeriod()).isEqualTo(SalaryPeriod.YEAR);
        assertThat(ps.get(1).salaryMin()).isEqualByComparingTo(new BigDecimal("190000"));
        assertThat(ps.get(1).salaryMax()).isEqualByComparingTo(new BigDecimal("330000"));
        assertThat(ps.get(2).salaryMin()).isNull();                       // summaryComponents [] and tiers []
        assertThat(ps.get(2).salaryMax()).isNull();
        assertThat(ps.get(2).salaryPeriod()).isNull();
        assertThat(ps.get(2).salaryCurrency()).isNull();
    }

    @Test
    void salaryAcrossTiersIsMinOfMinsMaxOfMaxes() {
        JsonNode root = Fixtures.tree("ashby.json");
        ((ObjectNode) job(root, 1).get("compensation")).remove("summaryComponents");
        RawPosting p = parse(root).postings().get(1);
        assertThat(p.salaryMin()).isEqualByComparingTo(new BigDecimal("190000"));
        assertThat(p.salaryMax()).isEqualByComparingTo(new BigDecimal("330000"));
        assertThat(p.salaryPeriod()).isEqualTo(SalaryPeriod.YEAR);
        assertThat(p.salaryCurrency()).isEqualTo("USD");
    }

    @Test
    void salaryWithOnlyEquityOrNoneIntervalIsAbsent() throws Exception {
        JsonNode comp = Fixtures.MAPPER.readTree("""
                {"summaryComponents":[
                  {"compensationType":"EquityPercentage","interval":"NONE","currencyCode":null,"minValue":null,"maxValue":null},
                  {"compensationType":"EquityCashValue","interval":"1 YEAR","currencyCode":"USD","minValue":10000,"maxValue":20000},
                  {"compensationType":"Salary","interval":"NONE","currencyCode":"USD","minValue":50000,"maxValue":60000},
                  {"compensationType":"Salary","interval":"1 YEAR","currencyCode":"USD","minValue":null,"maxValue":90000}
                ]}""");
        assertThat(AshbyAdapter.salary(comp)).isNull();
        assertThat(AshbyAdapter.salary(Fixtures.MAPPER.readTree("{\"summaryComponents\":[],\"compensationTiers\":[]}")))
                .isNull();
        assertThat(AshbyAdapter.salary(NullNode.getInstance())).isNull();
    }

    @Test
    void salaryMonthAndHourIntervals() throws Exception {
        AshbyAdapter.Salary month = AshbyAdapter.salary(Fixtures.MAPPER.readTree("""
                {"summaryComponents":[{"compensationType":"Salary","interval":"1 MONTH","currencyCode":"EUR","minValue":4000,"maxValue":5000}]}"""));
        assertThat(month.period()).isEqualTo(SalaryPeriod.MONTH);
        assertThat(month.currency()).isEqualTo("EUR");
        AshbyAdapter.Salary hour = AshbyAdapter.salary(Fixtures.MAPPER.readTree("""
                {"summaryComponents":[{"compensationType":"Salary","interval":"1 HOUR","currencyCode":"USD","minValue":20,"maxValue":30}]}"""));
        assertThat(hour.period()).isEqualTo(SalaryPeriod.HOUR);
    }

    @Test
    void compensationKeyMissingMeansNoSalary() {
        JsonNode root = Fixtures.tree("ashby.json");
        job(root, 0).remove("compensation");
        assertThat(parse(root).postings().get(0).salaryMin()).isNull();
    }

    @Test
    void htmlDescriptionUsedWhenNoPlain() {
        JsonNode root = Fixtures.tree("ashby.json");
        job(root, 0).remove("descriptionPlain");
        job(root, 0).put("descriptionHtml", "<p>Hello</p>");
        RawPosting p = parse(root).postings().get(0);
        assertThat(p.descriptionHtmlOrText()).isEqualTo("<p>Hello</p>");
        assertThat(p.descriptionIsHtml()).isTrue();
    }

    @Test
    void probeNotFoundTextPlainIsEmptyWithoutJsonParse() throws Exception {
        try (FeedStubServer stub = new FeedStubServer()) {
            SourceProperties p = Fixtures.stubProps(stub.baseUrl(), stub.baseUrl());
            AshbyAdapter ashby = new AshbyAdapter(Fixtures.client(p), p);
            stub.handler(FeedStubServer.bytes(404, "text/plain", "Not Found".getBytes(StandardCharsets.UTF_8),
                    Map.of()));
            assertThat(ashby.probe("nope", Map.of())).isEmpty();
            assertThat(stub.last().uri().getPath()).isEqualTo("/posting-api/job-board/nope");

            stub.handler(FeedStubServer.json(200, "{\"apiVersion\":\"1\",\"jobs\":[]}"));
            assertThat(ashby.probe("empty", Map.of())).get().extracting(BoardInfo::warning)
                    .isEqualTo(BoardInfo.NO_OPEN_POSTINGS);

            stub.handler(FeedStubServer.bytes(200, "application/json", Fixtures.bytes("ashby.json"), Map.of()));
            assertThat(ashby.probe("ramp", Map.of())).get().extracting(BoardInfo::openPostings).isEqualTo(3);

            FetchResult result = ashby.fetch(target, FetchRequest.NONE);
            assertThat(result.postings()).hasSize(3);
            assertThat(stub.last().uri().getQuery()).isEqualTo("includeCompensation=true");
        }
    }

    @Test
    void probeServerErrorWithTextBodyIsSourceException() throws Exception {
        try (FeedStubServer stub = new FeedStubServer()) {
            SourceProperties p = Fixtures.stubProps(stub.baseUrl(), stub.baseUrl());
            AshbyAdapter ashby = new AshbyAdapter(Fixtures.client(p), p);
            stub.handler(FeedStubServer.bytes(503, "text/plain", "Service Unavailable".getBytes(StandardCharsets.UTF_8),
                    Map.of()));
            SourceException e = org.junit.jupiter.api.Assertions.assertThrows(SourceException.class,
                    () -> ashby.probe("ramp", Map.of()));
            assertThat(e.kind()).isEqualTo(SourceFailure.Kind.SERVER_ERROR);
            assertThat(e.getMessage()).doesNotContain("Service Unavailable");
        }
    }

}
