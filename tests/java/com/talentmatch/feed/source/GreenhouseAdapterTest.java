package com.talentmatch.feed.source;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.talentmatch.preferences.Workplace;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** §9.1 item 1 (Greenhouse) with the probe-findings corrections to §4.2.1. */
class GreenhouseAdapterTest {

    private static final String WHERE = "boards-api.greenhouse.io/v1/boards/gitlab/jobs";
    private final SourceProperties props = Fixtures.props(5000);
    private final GreenhouseAdapter adapter = new GreenhouseAdapter(Fixtures.client(props), props);
    private final SourceTarget target = new SourceTarget("gitlab", "Owner Name For GitLab");

    private PostingFields.ParsedListing list() {
        return adapter.parseListing(Fixtures.bytes("greenhouse-list.json"), target, WHERE);
    }

    @Test
    void listMapsFourPostingsWithCompanyFromThePosting() {
        PostingFields.ParsedListing parsed = list();
        assertThat(parsed.skipped()).isZero();
        assertThat(parsed.postings()).hasSize(4);
        assertThat(parsed.postings()).extracting(RawPosting::company).containsOnly("GitLab");
        RawPosting first = parsed.postings().get(0);
        assertThat(first.externalId()).isEqualTo("8638232002");
        assertThat(first.title()).isEqualTo("AI Transformation Owner, CRO");
        assertThat(first.url()).isEqualTo("https://job-boards.greenhouse.io/gitlab/jobs/8638232002");
        assertThat(first.locationText()).isEqualTo("Remote, United States");
        assertThat(first.salaryMin()).isNull();
        assertThat(first.salaryPeriod()).isNull();
        assertThat(first.salaryEstimated()).isFalse();
    }

    @Test
    void listFirstPublishedIsPostedAtAndUpdatedAtIsContentVersion() {
        RawPosting first = list().postings().get(0);
        assertThat(first.postedAt()).isEqualTo(OffsetDateTime.parse("2026-07-22T13:38:40-04:00").toInstant());
        assertThat(first.contentVersion()).isEqualTo(OffsetDateTime.parse("2026-09-29T10:38:50-04:00").toInstant());
        assertThat(first.sourceUpdatedAt()).isEqualTo(first.contentVersion());
        assertThat(list().postings()).allSatisfy(p -> {
            assertThat(p.descriptionComplete()).isFalse();
            assertThat(p.descriptionHtmlOrText()).isNull();
            assertThat(p.contentVersion()).isNotNull();
        });
    }

    @Test
    void listNullFirstPublishedGivesNullPostedAtNeverUpdatedAt() {
        RawPosting noFirst = list().postings().stream()
                .filter(p -> p.externalId().equals("8785825002")).findFirst().orElseThrow();
        assertThat(noFirst.postedAt()).isNull();
        assertThat(noFirst.sourceUpdatedAt()).isNotNull();
        assertThat(noFirst.contentVersion()).isEqualTo(OffsetDateTime.parse("2026-09-29T10:38:50-04:00").toInstant());
    }

    @Test
    void listMissingFirstPublishedKeyAlsoGivesNullPostedAt() {
        ObjectNode root = (ObjectNode) Fixtures.tree("greenhouse-list.json");
        ((ObjectNode) root.get("jobs").get(0)).remove("first_published");
        RawPosting p = adapter.parseListing(Fixtures.write(root), target, WHERE).postings().get(0);
        assertThat(p.postedAt()).isNull();
        assertThat(p.contentVersion()).isNotNull();
    }

    @Test
    void listCountriesAndWorkplace() {
        assertThat(list().postings()).extracting(RawPosting::countryCode).containsExactly("US", "CA", "FR", "IE");
        assertThat(list().postings()).extracting(RawPosting::workplace).containsOnly(Workplace.REMOTE);
    }

    @Test
    void listCompanyFallsBackToSourceNameThenToken() {
        ObjectNode root = (ObjectNode) Fixtures.tree("greenhouse-list.json");
        ((ObjectNode) root.get("jobs").get(0)).remove("company_name");
        ((ObjectNode) root.get("jobs").get(1)).put("company_name", "  ");
        byte[] body = Fixtures.write(root);
        assertThat(adapter.parseListing(body, target, WHERE).postings())
                .extracting(RawPosting::company).startsWith("Owner Name For GitLab", "Owner Name For GitLab");
        assertThat(adapter.parseListing(body, new SourceTarget("gitlab", null), WHERE).postings().get(0).company())
                .isEqualTo("gitlab");
    }

    // ---- detail ----

    private RawPosting detail() {
        return adapter.parseDetail(Fixtures.bytes("greenhouse-detail.json"), target, WHERE);
    }

    @Test
    void detailContentIsUnescapedOnceIncludingDoubleEscapedNbsp() {
        RawPosting p = detail();
        String html = p.descriptionHtmlOrText();
        assertThat(p.descriptionIsHtml()).isTrue();
        assertThat(p.descriptionComplete()).isTrue();
        assertThat(html).startsWith("<div class=\"content-intro\"><p>GitLab is");
        assertThat(html).doesNotContain("&lt;", "&gt;", "&quot;", "&amp;nbsp;");
        assertThat(html).contains("&nbsp;");                         // &amp;nbsp; → &nbsp; (once only)

        String text = HtmlText.toText(html);
        assertThat(text).startsWith("GitLab is the intelligent orchestration platform");
        assertThat(text).doesNotContain("<", ">", "&nbsp;", "&amp;", "&lt;", " ");
        assertThat(text).contains("Enablement & AI.");                // &amp;amp; → & after both decodes
        assertThat(text).contains("What You’ll Do");
        assertThat(text).contains("\n- ");                            // list items survive as lines
        assertThat(HtmlText.escapedToText(Fixtures.tree("greenhouse-detail.json").get("content").asText()))
                .isEqualTo(text);
    }

    @Test
    void detailSalaryFromFirstPayRangeWithoutPeriod() {
        RawPosting p = detail();
        assertThat(p.salaryMin()).isEqualByComparingTo(new BigDecimal("139200.00"));
        assertThat(p.salaryMax()).isEqualByComparingTo(new BigDecimal("235200.00"));
        assertThat(p.salaryMin().scale()).isEqualTo(2);
        assertThat(p.salaryCurrency()).isEqualTo("USD");
        assertThat(p.salaryPeriod()).isNull();
        assertThat(p.salaryEstimated()).isFalse();
    }

    @Test
    void detailEmptyPayRangesMeansNoSalary() {
        ObjectNode job = (ObjectNode) Fixtures.tree("greenhouse-detail.json");
        job.putArray("pay_input_ranges");
        RawPosting p = adapter.parseDetail(Fixtures.write(job), target, WHERE);
        assertThat(p.salaryMin()).isNull();
        assertThat(p.salaryMax()).isNull();
        assertThat(p.salaryCurrency()).isNull();
    }

    @Test
    void detailEmptyOfficesWorksAndOtherFieldsMap() {
        RawPosting p = detail();
        assertThat(Fixtures.tree("greenhouse-detail.json").get("offices")).isEmpty();
        assertThat(p.countryCode()).isEqualTo("US");
        assertThat(p.workplace()).isEqualTo(Workplace.REMOTE);
        assertThat(p.company()).isEqualTo("GitLab");
        assertThat(p.externalId()).isEqualTo("8638232002");
        assertThat(p.postedAt()).isEqualTo(OffsetDateTime.parse("2026-07-22T13:38:40-04:00").toInstant());
        assertThat(p.contentVersion()).isEqualTo(OffsetDateTime.parse("2026-09-29T10:38:50-04:00").toInstant());
    }

    @Test
    void detailCountryFromOfficesWhenLocationHasNone() {
        ObjectNode job = (ObjectNode) Fixtures.tree("greenhouse-detail.json");
        ((ObjectNode) job.get("location")).put("name", "Remote");
        ArrayNode offices = job.putArray("offices");
        offices.addObject().put("name", "EMEA").putObject("location").put("name", "Dublin, Ireland");
        assertThat(adapter.parseDetail(Fixtures.write(job), target, WHERE).countryCode()).isEqualTo("IE");

        offices.removeAll();
        assertThat(adapter.parseDetail(Fixtures.write(job), target, WHERE).countryCode()).isNull();
    }

    @Test
    void detailWithoutIdIsInvalidResponse() {
        ObjectNode job = (ObjectNode) Fixtures.tree("greenhouse-detail.json");
        job.remove("id");
        SourceException e = org.junit.jupiter.api.Assertions.assertThrows(SourceException.class,
                () -> adapter.parseDetail(Fixtures.write(job), target, WHERE));
        assertThat(e.kind()).isEqualTo(SourceFailure.Kind.INVALID_RESPONSE);
    }

    // ---- over HTTP (stub) ----

    @Test
    void detailNotFoundIsEmptyAndProbeReturnsBoardName() throws Exception {
        try (FeedStubServer stub = new FeedStubServer()) {
            SourceProperties p = Fixtures.stubProps(stub.baseUrl(), stub.baseUrl());
            GreenhouseAdapter gh = new GreenhouseAdapter(Fixtures.client(p), p);

            stub.handler(FeedStubServer.json(404, "{\"error\":\"Job not found\"}"));
            assertThat(gh.fetchDetail(target, "123")).isEmpty();
            assertThat(stub.last().uri().getPath()).isEqualTo("/v1/boards/gitlab/jobs/123");
            assertThat(stub.last().uri().getQuery()).isEqualTo("pay_transparency=true");

            stub.handler(FeedStubServer.json(200, "{\"name\":\"GitLab\",\"content\":\"<p>We &amp; you</p>\"}"));
            Optional<BoardInfo> info = gh.probe("gitlab", Map.of());
            assertThat(info).isPresent();
            assertThat(info.get().companyName()).isEqualTo("GitLab");
            assertThat(stub.last().uri().getPath()).isEqualTo("/v1/boards/gitlab");

            stub.handler(FeedStubServer.json(404, "{\"error\":\"Job board not found\"}"));
            assertThat(gh.probe("nope", Map.of())).isEmpty();

            stub.handler(FeedStubServer.bytes(200, "application/json", Fixtures.bytes("greenhouse-detail.json"),
                    Map.of()));
            assertThat(gh.fetchDetail(target, "8638232002")).get()
                    .extracting(RawPosting::salaryCurrency).isEqualTo("USD");
        }
    }

    @Test
    void fetchMapsListingAndCarriesEtag() throws Exception {
        try (FeedStubServer stub = new FeedStubServer()) {
            SourceProperties p = Fixtures.stubProps(stub.baseUrl(), stub.baseUrl());
            GreenhouseAdapter gh = new GreenhouseAdapter(Fixtures.client(p), p);
            stub.handler(FeedStubServer.bytes(200, "application/json", Fixtures.bytes("greenhouse-list.json"),
                    Map.of("ETag", "W/\"gh1\"")));
            FetchResult result = gh.fetch(target, FetchRequest.NONE);
            assertThat(result.notModified()).isFalse();
            assertThat(result.complete()).isTrue();
            assertThat(result.postings()).hasSize(4);
            assertThat(result.etag()).isEqualTo("W/\"gh1\"");
            assertThat(result.bodyHash()).hasSize(64);
            assertThat(stub.last().uri().getPath()).isEqualTo("/v1/boards/gitlab/jobs");
        }
    }

}
