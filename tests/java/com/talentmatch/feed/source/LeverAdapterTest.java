package com.talentmatch.feed.source;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.talentmatch.preferences.SalaryPeriod;
import com.talentmatch.preferences.Workplace;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** §9.1 item 1 (Lever) with the probe-findings corrections to §4.2.2. */
class LeverAdapterTest {

    private static final String WHERE = "api.lever.co/v0/postings/outreach";
    private final SourceProperties props = Fixtures.props(5000);
    private final LeverAdapter adapter = new LeverAdapter(Fixtures.client(props), props);
    private final SourceTarget target = new SourceTarget("outreach", "Outreach");

    private List<RawPosting> postings() {
        PostingFields.ParsedListing parsed = adapter.parseListing(Fixtures.bytes("lever.json"), target, WHERE);
        assertThat(parsed.skipped()).isZero();
        return parsed.postings();
    }

    private RawPosting byId(String id) {
        return postings().stream().filter(p -> p.externalId().equals(id)).findFirst().orElseThrow();
    }

    @Test
    void mapsAllFourPostings() {
        assertThat(postings()).hasSize(4);
        RawPosting p = postings().get(0);
        assertThat(p.externalId()).isEqualTo("4b20d94b-ecae-4761-86ae-4f474d091adc");
        assertThat(p.title()).isEqualTo("Staff Software Engineer, Identity and Access Platform");
        assertThat(p.url()).isEqualTo("https://jobs.lever.co/outreach/4b20d94b-ecae-4761-86ae-4f474d091adc");
        assertThat(p.company()).isEqualTo("Outreach");
        assertThat(p.countryCode()).isEqualTo("US");
        assertThat(p.workplace()).isEqualTo(Workplace.HYBRID);
        assertThat(p.employmentType()).isEqualTo("Full-Time");
        assertThat(p.locationText()).isEqualTo("Seattle, WA");
        assertThat(p.contentVersion()).isNull();
        assertThat(byId("13e953ce-8527-436c-be6c-55d3b659dd60").countryCode()).isEqualTo("IN");
        assertThat(byId("13e953ce-8527-436c-be6c-55d3b659dd60").workplace()).isEqualTo(Workplace.REMOTE);
        assertThat(byId("3c6ba2dd-37ce-48ba-a01a-a890b6271c63").locationText()).isEqualTo("Seattle, WA; Atlanta, GA");
    }

    @Test
    void createdAtEpochMillisIsPostedAt() {
        assertThat(postings().get(0).postedAt()).isEqualTo(Instant.ofEpochMilli(1727202805687L));
    }

    @Test
    void descriptionComposesPlainBodyListsAndAdditional() {
        RawPosting p = postings().get(0);
        assertThat(p.descriptionIsHtml()).isFalse();
        assertThat(p.descriptionComplete()).isTrue();
        String d = p.descriptionHtmlOrText();
        assertThat(d).startsWith("About Outreach");
        assertThat(d).contains("The Role");                                   // descriptionBodyPlain
        assertThat(d).contains("Our Vision of You:\n- 8+ years of professional software development experience.");
        assertThat(d).contains("Your Daily Adventures:\n- Design, build, and operate services");
        assertThat(d).contains("#LI-XX1").contains("Flexible time off");      // additionalPlain
        assertThat(d).doesNotContain("<li>", "</li>");
        assertThat(d.indexOf("Our Vision of You:")).isLessThan(d.indexOf("#LI-XX1"));
        assertThat(d.indexOf("About Outreach")).isLessThan(d.indexOf("Our Vision of You:"));
    }

    @Test
    void yearlySalary() {
        RawPosting p = postings().get(0);
        assertThat(p.salaryMin()).isEqualByComparingTo(new BigDecimal("165000"));
        assertThat(p.salaryMax()).isEqualByComparingTo(new BigDecimal("215000"));
        assertThat(p.salaryCurrency()).isEqualTo("USD");
        assertThat(p.salaryPeriod()).isEqualTo(SalaryPeriod.YEAR);
        assertThat(byId("13e953ce-8527-436c-be6c-55d3b659dd60").salaryMin()).isNull();
        assertThat(byId("13e953ce-8527-436c-be6c-55d3b659dd60").salaryPeriod()).isNull();
    }

    @Test
    void hourlyWagePostingWithNullCountryAndNoCommitment() {
        RawPosting p = byId("00000000-0000-4000-8000-000000000001");
        assertThat(p.salaryMin()).isEqualByComparingTo(new BigDecimal("24"));
        assertThat(p.salaryMax()).isEqualByComparingTo(new BigDecimal("30"));
        assertThat(p.salaryPeriod()).isEqualTo(SalaryPeriod.HOUR);
        assertThat(p.countryCode()).isNull();                 // "Atlanta, GA" is not Gabon
        assertThat(p.employmentType()).isNull();
        assertThat(p.workplace()).isEqualTo(Workplace.UNKNOWN);  // "unspecified"
    }

    @Test
    void monthlySalaryInterval() {
        ArrayNode root = (ArrayNode) Fixtures.tree("lever.json");
        ((ObjectNode) root.get(0).get("salaryRange")).put("interval", "per-month-salary");
        RawPosting p = adapter.parseListing(Fixtures.write(root), target, WHERE).postings().get(0);
        assertThat(p.salaryPeriod()).isEqualTo(SalaryPeriod.MONTH);
    }

    @Test
    void probeEmptyArrayWarnsAndNotFoundIsEmpty() throws Exception {
        try (FeedStubServer stub = new FeedStubServer()) {
            SourceProperties p = Fixtures.stubProps(stub.baseUrl(), stub.baseUrl());
            LeverAdapter lever = new LeverAdapter(Fixtures.client(p), p);

            stub.handler(FeedStubServer.json(200, "[]"));
            Optional<BoardInfo> info = lever.probe("quietco", Map.of());
            assertThat(info).isPresent();
            assertThat(info.get().warning()).isEqualTo(BoardInfo.NO_OPEN_POSTINGS);
            assertThat(info.get().openPostings()).isZero();

            stub.handler(FeedStubServer.json(200, "[{\"id\":\"x\"}]"));
            assertThat(lever.probe("busyco", Map.of())).get().extracting(BoardInfo::warning).isNull();

            stub.handler(FeedStubServer.json(404, "{\"ok\":false,\"error\":\"Document not found\"}"));
            assertThat(lever.probe("nope", Map.of())).isEmpty();
        }
    }

    @Test
    void euInstanceHostAndCaseSensitiveToken() throws Exception {
        try (FeedStubServer global = new FeedStubServer(); FeedStubServer eu = new FeedStubServer()) {
            SourceProperties p = Fixtures.stubProps(global.baseUrl(), eu.baseUrl());
            LeverAdapter lever = new LeverAdapter(Fixtures.client(p), p);
            eu.handler(FeedStubServer.bytes(200, "application/json", Fixtures.bytes("lever.json"), Map.of()));
            global.handler(FeedStubServer.json(500, "{}"));

            FetchResult result = lever.fetch(new SourceTarget("MixedCase-Co", null, Map.of("leverInstance", "eu")),
                    FetchRequest.NONE);
            assertThat(result.postings()).hasSize(4);
            assertThat(result.postings().get(0).company()).isEqualTo("MixedCase-Co");
            assertThat(global.requests()).isEmpty();
            assertThat(eu.last().uri().getPath()).isEqualTo("/v0/postings/MixedCase-Co");
            assertThat(eu.last().uri().getQuery()).isEqualTo("mode=json");

            eu.handler(FeedStubServer.json(200, "[]"));
            lever.probe("MixedCase-Co", Map.of("leverInstance", "EU"));
            assertThat(eu.last().uri().getPath()).isEqualTo("/v0/postings/MixedCase-Co");
            assertThat(global.requests()).isEmpty();

            global.handler(FeedStubServer.json(200, "[]"));
            lever.probe("Spotify", Map.of());
            assertThat(global.last().uri().getPath()).isEqualTo("/v0/postings/Spotify");
        }
    }
}
