package com.talentmatch.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.talentmatch.application.content.Bullet;
import com.talentmatch.application.content.CertificationItem;
import com.talentmatch.application.content.CoverLetterContent;
import com.talentmatch.application.content.DocumentKind;
import com.talentmatch.application.content.EducationItem;
import com.talentmatch.application.content.ExperienceItem;
import com.talentmatch.application.content.Finding;
import com.talentmatch.application.content.FindingCode;
import com.talentmatch.application.content.Header;
import com.talentmatch.application.content.LanguageItem;
import com.talentmatch.application.content.LinkItem;
import com.talentmatch.application.content.ProjectItem;
import com.talentmatch.application.content.Severity;
import com.talentmatch.application.content.TailoredCvContent;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Phase 7 step 5: the stored JSON of document versions ({@link DocumentJson}). */
class DocumentJsonTest {

    private static final Header HEADER = new Header("Zoë Ndlovu-Müller", "zoe@example.com", "+27 82 555 0199",
            "Cape Town", List.of(new LinkItem("GitHub", "https://github.com/zoe-nm")));

    static TailoredCvContent cv() {
        return new TailoredCvContent(HEADER, "Backend engineer", "Summary.", List.of("Java", "PostgreSQL"),
                List.of(new ExperienceItem("E1", "Senior Backend Engineer", "Acme Ltd", "Cape Town", "2021-03", null,
                        true, List.of("Java 17"), List.of(new Bullet("E1.H1", "Cut API latency by 40%")))),
                List.of(new ProjectItem("P1", "Ledger", "Open-source ledger", List.of("Go"), null,
                        List.of(new Bullet("P1.H1", "Wrote it")))),
                List.of(new CertificationItem("C1", "AWS Certified Developer", "Amazon", "2022-05", "2025-05")),
                List.of(new EducationItem("D1", "University of Stellenbosch", "BSc", "Computer Science", "2012",
                        "2015")),
                List.of(new LanguageItem("English", "Native")));
    }

    static CoverLetterContent letter() {
        return new CoverLetterContent(HEADER, "Initech", "Senior Java Developer", LocalDate.of(2026, 1, 5),
                "Dear Hiring Manager,", List.of("First \"quoted\" paragraph.", "Second <b> & paragraph."),
                "Kind regards,", "Zoë Ndlovu-Müller");
    }

    @Test
    void aCvRoundTrips() {
        TailoredCvContent cv = cv();
        String json = DocumentJson.write(cv);
        assertThat(DocumentJson.content(DocumentKind.CV, json)).isEqualTo(cv);
    }

    @Test
    void aLetterRoundTripsWithAnIsoDate() throws Exception {
        CoverLetterContent letter = letter();
        String json = DocumentJson.write(letter);
        JsonNode tree = new ObjectMapper().readTree(json);
        assertThat(tree.get("date").isTextual()).isTrue();
        assertThat(tree.get("date").asText()).as("yyyy-MM-dd, not an array or timestamp").isEqualTo("2026-01-05");
        assertThat(DocumentJson.content(DocumentKind.COVER_LETTER, json)).isEqualTo(letter);
    }

    @Test
    void nullsAndUnicodeSurvive() {
        CoverLetterContent letter = new CoverLetterContent(new Header("Zoë", null, null, null, null), "Initech", "Dev",
                LocalDate.of(2026, 12, 31), "Dear Hiring Manager,", List.of("Ünïcödé — “quotes”"), "Kind regards,",
                "Zoë");
        Object back = DocumentJson.content(DocumentKind.COVER_LETTER, DocumentJson.write(letter));
        assertThat(back).isEqualTo(letter);
        assertThat(((CoverLetterContent) back).header().links()).isEmpty();
    }

    @Test
    void unknownStoredFieldsAreIgnored() {
        String json = DocumentJson.write(letter()).replaceFirst("\\{", "{\"futureField\": 1, ");
        assertThat(DocumentJson.content(DocumentKind.COVER_LETTER, json)).isEqualTo(letter());
    }

    @Test
    void findingsRoundTrip() {
        List<Finding> findings = List.of(
                Finding.blocking(FindingCode.NUMBER_NOT_IN_PROFILE, "paragraphs[1]", "12", "The number 12 ..."),
                Finding.info(FindingCode.REVERTED, "experience[0].highlights[0]", "E1.H1", "Reverted."),
                new Finding(Severity.WARNING, FindingCode.LENGTH, null, null, "Too short."));
        String json = DocumentJson.write(findings);
        assertThat(json).startsWith("[");
        assertThat(DocumentJson.findings(json)).isEqualTo(findings);
        assertThat(DocumentJson.findings("[]")).isEmpty();
        assertThat(DocumentJson.findings(null)).isEmpty();
    }

    @Test
    void unreadableJsonThrowsWithoutQuotingIt() {
        String secret = "{\"paragraphs\": [\"very private text\"";
        assertThatThrownBy(() -> DocumentJson.content(DocumentKind.COVER_LETTER, secret))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Stored document JSON could not be read");
        assertThatThrownBy(() -> DocumentJson.findings("[{\"severity\": \"NOPE\"}]"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Stored findings JSON could not be read");
    }
}
