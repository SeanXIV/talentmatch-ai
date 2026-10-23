package com.talentmatch.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.talentmatch.application.DocumentRepository;
import com.talentmatch.application.DocumentRepository.DocumentRef;
import com.talentmatch.application.DocumentRepository.NewVersion;
import com.talentmatch.application.content.CoverLetterContent;
import com.talentmatch.application.content.DocumentKind;
import com.talentmatch.application.content.DocumentSource;
import com.talentmatch.application.content.Finding;
import com.talentmatch.application.content.FindingCode;
import com.talentmatch.application.content.Header;
import com.talentmatch.support.AbstractApiIT;
import com.talentmatch.support.Api.Res;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

/** Phase 7 step 5: {@link DocumentRepository#insertVersion} numbering and stored columns, against PG16. */
class ApplicationDocumentRepositoryIT extends AbstractApiIT {

    private static final String HASH = "b".repeat(64);

    @Autowired
    private DocumentRepository documents;

    private int profileVersion;
    private UUID appA;
    private UUID appB;

    @BeforeEach
    void fixtures() {
        skills("Java");
        Res p = api.put("/api/profile", "{\"profile\": {\"fullName\": \"Ada Lovelace\", \"email\": \"ada@example.com\", "
                + "\"skills\": [{\"name\": \"Java\"}]}}");
        assertThat(p.status()).as(p.toString()).isEqualTo(200);
        profileVersion = p.json().get("version").asInt();
        appA = application(job("Engineer A", "Acme", "Java"));
        appB = application(job("Engineer B", "Acme", "Java"));
    }

    private UUID application(UUID jobId) {
        Res r = api.post("/api/applications", "{\"jobId\": \"" + jobId + "\"}");
        assertThat(r.status()).as(r.toString()).isEqualTo(201);
        return UUID.fromString(r.json().get("id").asText());
    }

    private static CoverLetterContent letter(String paragraph) {
        return new CoverLetterContent(new Header("Ada Lovelace", null, null, null, List.of()), "Acme", "Engineer A",
                LocalDate.of(2026, 10, 19), "Dear Hiring Manager,", List.of(paragraph), "Kind regards,",
                "Ada Lovelace");
    }

    private NewVersion ai(UUID app, DocumentKind kind, Object content, List<Finding> findings, Instant jobUpdatedAt) {
        return NewVersion.ai(app, kind, profileVersion, jobUpdatedAt, content, findings, "fake/tailor", HASH);
    }

    @Test
    void versionsAreNumberedPerApplicationAndKind() {
        Instant jobAt = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        DocumentRef a1 = documents.insertVersion(ai(appA, DocumentKind.COVER_LETTER, letter("one"), List.of(), jobAt));
        DocumentRef a2 = documents.insertVersion(ai(appA, DocumentKind.COVER_LETTER, letter("two"), List.of(), null));
        DocumentRef b1 = documents.insertVersion(ai(appB, DocumentKind.COVER_LETTER, letter("b"), List.of(), null));
        DocumentRef cv1 = documents.insertVersion(ai(appA, DocumentKind.CV, Map.of("skills", List.of("Java")),
                List.of(), null));
        DocumentRef a3 = documents.insertVersion(new NewVersion(appA, DocumentKind.COVER_LETTER, DocumentSource.EDIT,
                a2.id(), profileVersion, null, letter("three"), null, null, null));

        assertThat(a1.version()).isEqualTo(1);
        assertThat(a2.version()).isEqualTo(2);
        assertThat(a3.version()).isEqualTo(3);
        assertThat(b1.version()).as("per application").isEqualTo(1);
        assertThat(cv1.version()).as("per kind").isEqualTo(1);
        assertThat(List.of(a1, a2, a3, b1, cv1)).allSatisfy(r -> assertThat(r.approved()).isFalse());
        assertThat(a1.kind()).isEqualTo(DocumentKind.COVER_LETTER);
        assertThat(cv1.kind()).isEqualTo(DocumentKind.CV);

        Map<String, Object> row1 = jdbc.queryForMap("SELECT * FROM application_document WHERE id = ?", a1.id());
        assertThat(row1.get("source")).isEqualTo("AI");
        assertThat(row1.get("model")).isEqualTo("fake/tailor");
        assertThat(row1.get("input_hash")).isEqualTo(HASH);
        assertThat(row1.get("profile_version")).isEqualTo(profileVersion);
        assertThat(row1.get("based_on_id")).isNull();
        assertThat(((java.sql.Timestamp) row1.get("job_updated_at")).toInstant()).isEqualTo(jobAt);
        assertThat(row1.get("approved_at")).isNull();

        Map<String, Object> row3 = jdbc.queryForMap("SELECT * FROM application_document WHERE id = ?", a3.id());
        assertThat(row3.get("source")).isEqualTo("EDIT");
        assertThat(row3.get("based_on_id")).isEqualTo(a2.id());
        assertThat(row3.get("model")).isNull();
        assertThat(row3.get("input_hash")).isNull();
        assertThat(row3.get("job_updated_at")).isNull();

        assertThat(documents.findRef(appA, a3.id())).hasValueSatisfying(r -> assertThat(r.version()).isEqualTo(3));
        assertThat(documents.findRef(appB, a3.id())).isEmpty();
    }

    @Test
    void contentAndFindingsAreStoredAsJsonWithIsoDates() throws Exception {
        Finding f = Finding.blocking(FindingCode.NUMBER_NOT_IN_PROFILE, "paragraphs[0]", "12", "msg");
        DocumentRef ref = documents.insertVersion(ai(appA, DocumentKind.COVER_LETTER, letter("p"), List.of(f), null));
        String content = jdbc.queryForObject("SELECT content::text FROM application_document WHERE id = ?",
                String.class, ref.id());
        JsonNode c = mapper.readTree(content);
        assertThat(c.get("date").asText()).isEqualTo("2026-10-19");
        assertThat(c.get("paragraphs").get(0).asText()).isEqualTo("p");
        assertThat(c.at("/header/fullName").asText()).isEqualTo("Ada Lovelace");
        JsonNode findings = mapper.readTree(jdbc.queryForObject("SELECT findings::text FROM application_document "
                + "WHERE id = ?", String.class, ref.id()));
        assertThat(findings.size()).isEqualTo(1);
        assertThat(findings.at("/0/severity").asText()).isEqualTo("BLOCKING");
        assertThat(findings.at("/0/code").asText()).isEqualTo("NUMBER_NOT_IN_PROFILE");
        assertThat(findings.at("/0/value").asText()).isEqualTo("12");

        JsonNode view = api.get("/api/applications/" + appA).json();
        assertThat(view.at("/documents/coverLetter/latest/findingCounts/blocking").asInt()).isEqualTo(1);
        assertThat(view.at("/documents/coverLetter/versions").asInt()).isEqualTo(1);
    }

    @Test
    void anEditBasedOnAnotherApplicationsVersionIsRejected() {
        DocumentRef b1 = documents.insertVersion(ai(appB, DocumentKind.COVER_LETTER, letter("b"), List.of(), null));
        assertThatThrownBy(() -> documents.insertVersion(new NewVersion(appA, DocumentKind.COVER_LETTER,
                DocumentSource.EDIT, b1.id(), profileVersion, null, letter("x"), null, null, null)))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM application_document WHERE application_id = ?",
                Integer.class, appA)).isZero();
    }

    @Test
    void newVersionRequiresItsKeyFields() {
        assertThatThrownBy(() -> new NewVersion(null, DocumentKind.CV, DocumentSource.AI, null, 1, null, "c", null,
                "m", HASH)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new NewVersion(appA, null, DocumentSource.AI, null, 1, null, "c", null, "m", HASH))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new NewVersion(appA, DocumentKind.CV, null, null, 1, null, "c", null, "m", HASH))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new NewVersion(appA, DocumentKind.CV, DocumentSource.AI, null, 1, null, null, null,
                "m", HASH)).isInstanceOf(NullPointerException.class);
        NewVersion v = new NewVersion(appA, DocumentKind.CV, DocumentSource.AI, null, 1, null, "c", null, "m", HASH);
        assertThat(v.findings()).isEmpty();
    }
}
