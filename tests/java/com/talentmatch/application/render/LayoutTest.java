package com.talentmatch.application.render;

import static com.talentmatch.application.render.RenderFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;

import com.talentmatch.application.content.Bullet;
import com.talentmatch.application.content.CoverLetterContent;
import com.talentmatch.application.content.ExperienceItem;
import com.talentmatch.application.content.Header;
import com.talentmatch.application.content.TailoredCvContent;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;

/** §9.1.9: CvLayout/LetterLayout block sequences, empty sections omitted, date formatting. */
class LayoutTest {

    @Test
    void theCvBlockSequence() {
        assertThat(CvLayout.layout(cv())).containsExactly(
                new Block.Title(NAME),
                new Block.ContactLine("zoe@example.com · +27 82 555 0199 · Cape Town · https://github.com/zoe-nm"),
                new Block.Subheading("Backend engineer", null),
                new Block.Paragraph("Summary text."),
                new Block.Heading("Skills"),
                new Block.Paragraph("Java, PostgreSQL"),
                new Block.Heading("Experience"),
                new Block.Subheading("Senior Backend Engineer — Acme Ltd", "Cape Town · Mar 2021 – Present"),
                new Block.BulletItem("Cut latency by 40%"),
                new Block.Spacer(),
                new Block.Subheading("Backend Engineer — Globex", "Johannesburg · Jan 2016 – Dec 2020"),
                new Block.BulletItem("Built ETL pipelines"),
                new Block.Heading("Projects"),
                new Block.Subheading("Ledger", "Go, PostgreSQL · https://ledger.example.org"),
                new Block.Paragraph("Double-entry ledger"),
                new Block.BulletItem("Wrote the core"),
                new Block.Heading("Certifications"),
                new Block.BulletItem("AWS Certified Developer — Amazon, May 2022, expires May 2025"),
                new Block.Heading("Education"),
                new Block.Subheading("BSc, Computer Science", "University of Stellenbosch · 2012 – 2015"),
                new Block.Heading("Languages"),
                new Block.Paragraph("English (Native), isiXhosa"));
    }

    @Test
    void emptySectionsAreOmittedAndNoFooterIsEmitted() {
        TailoredCvContent minimal = new TailoredCvContent(new Header(NAME, null, null, null, List.of()), " ", null,
                List.of(), List.of(new ExperienceItem("E1", "Engineer", "Acme", null, null, null, null, List.of(),
                        List.of(new Bullet("E1.H1", "  "), new Bullet("E1.H2", "Did things")))),
                List.of(), List.of(), List.of(), List.of());
        assertThat(CvLayout.layout(minimal)).containsExactly(
                new Block.Title(NAME),
                new Block.Heading("Experience"),
                new Block.Subheading("Engineer — Acme", null),
                new Block.BulletItem("Did things"));
        TailoredCvContent empty = new TailoredCvContent(null, null, null, null, null, null, null, null, null);
        assertThat(CvLayout.layout(empty)).isEmpty();
        assertThat(CvLayout.layout(cv())).noneMatch(b -> b instanceof Block.Footer);
        assertThat(LetterLayout.layout(letter())).noneMatch(b -> b instanceof Block.Footer);
    }

    @Test
    void roleLinesWithPartialFacts() {
        TailoredCvContent cv = new TailoredCvContent(null, null, null, List.of(), List.of(
                new ExperienceItem("E1", "Engineer", null, null, "2019", null, false, List.of(), List.of()),
                new ExperienceItem("E2", null, "Acme", "Durban", null, "2018-07", null, List.of(), List.of()),
                new ExperienceItem("E3", null, null, null, null, null, true, List.of(), List.of())),
                List.of(), List.of(), List.of(), List.of());
        assertThat(CvLayout.layout(cv)).containsExactly(
                new Block.Heading("Experience"),
                new Block.Subheading("Engineer", "2019"),
                new Block.Spacer(),
                new Block.Subheading("Acme", "Durban · Jul 2018"),
                new Block.Spacer(),
                new Block.Paragraph("Present"));
    }

    @Test
    void datesArePrintedAsWrittenAndLocaleIndependent() {
        Locale saved = Locale.getDefault();
        try {
            Locale.setDefault(Locale.GERMANY);
            assertThat(CvLayout.formatDate("2019-03")).isEqualTo("Mar 2019");
            assertThat(CvLayout.formatDate("2019-12")).isEqualTo("Dec 2019");
            assertThat(CvLayout.formatDate(" 2019-5 ")).isEqualTo("May 2019");
            assertThat(CvLayout.formatDate("2019")).isEqualTo("2019");
            assertThat(CvLayout.formatDate("2019-13")).isEqualTo("2019-13");
            assertThat(CvLayout.formatDate("Spring 2019")).isEqualTo("Spring 2019");
            assertThat(CvLayout.formatDate(" ")).isNull();
            assertThat(CvLayout.formatDate(null)).isNull();
            assertThat(LetterLayout.formatDate(LocalDate.of(2026, 10, 19))).isEqualTo("19 October 2026");
            assertThat(LetterLayout.formatDate(LocalDate.of(2026, 3, 5))).isEqualTo("5 March 2026");
        } finally {
            Locale.setDefault(saved);
        }
    }

    @Test
    void theLetterBlockSequence() {
        assertThat(LetterLayout.layout(letter())).containsExactly(
                new Block.ContactLine("zoe@example.com · +27 82 555 0199 · Cape Town · https://github.com/zoe-nm"),
                new Block.Spacer(),
                new Block.Paragraph("19 October 2026"),
                new Block.Spacer(),
                new Block.Paragraph("Hiring team, Initech"),
                new Block.Paragraph("Re: Senior Java Developer"),
                new Block.Spacer(),
                new Block.Paragraph("Dear Hiring Manager,"),
                new Block.Paragraph("First paragraph."),
                new Block.Paragraph("Second paragraph."),
                new Block.Paragraph("Kind regards,"),
                new Block.Paragraph(NAME));
    }

    @Test
    void aSparseLetterSkipsMissingParts() {
        CoverLetterContent sparse = new CoverLetterContent(null, " ", null, null, "Dear Hiring Manager,",
                java.util.Arrays.asList("Only paragraph.", null, " "), "Kind regards,", null);
        assertThat(LetterLayout.layout(sparse)).containsExactly(
                new Block.Paragraph("Dear Hiring Manager,"),
                new Block.Paragraph("Only paragraph."),
                new Block.Paragraph("Kind regards,"));
    }
}
