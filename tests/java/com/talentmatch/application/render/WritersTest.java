package com.talentmatch.application.render;

import static com.talentmatch.application.render.RenderFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.stream.Collectors;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFFooter;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.junit.jupiter.api.Test;

/**
 * §9.1.10: DocxWriter and PdfWriter read back with POI and PDFBox (text, not bytes: DOCX isn't
 * byte-deterministic). The PDF tests use a system TTF (see {@link RenderFixtures#pdfWriter()}).
 */
class WritersTest {

    private static final DocumentMeta CV_META = new DocumentMeta("CV – " + NAME, NAME, CREATED);
    private static final String UNSUPPORTED = "中"; // U+4E2D: not in DejaVu Sans, Ubuntu, Liberation or Arial
    /** U+10FFFD (plane 16 private use, a surrogate pair): no font maps it; one "?" per code point. */
    private static final String SUPPLEMENTARY = new String(Character.toChars(0x10FFFD));

    // ------------------------------------------------------------------ DOCX

    private static XWPFDocument docx(List<Block> blocks, boolean draft) throws IOException {
        byte[] bytes = new DocxWriter().write(blocks, CV_META, draft);
        assertThat(bytes).isNotEmpty();
        return new XWPFDocument(new ByteArrayInputStream(bytes));
    }

    private static List<String> texts(XWPFDocument doc) {
        return doc.getParagraphs().stream().map(XWPFParagraph::getText).filter(t -> !t.isEmpty()).toList();
    }

    private static String footers(XWPFDocument doc) {
        return doc.getFooterList().stream().map(XWPFFooter::getText).collect(Collectors.joining("\n"));
    }

    @Test
    void docxTextReadsBackInOrder() throws IOException {
        try (XWPFDocument doc = docx(CvLayout.layout(cv()), false)) {
            assertThat(texts(doc)).containsExactly(
                    NAME,
                    "zoe@example.com · +27 82 555 0199 · Cape Town · https://github.com/zoe-nm",
                    "Backend engineer",
                    "Summary text.",
                    "Skills",
                    "Java, PostgreSQL",
                    "Experience",
                    "Senior Backend Engineer — Acme Ltd\nCape Town · Mar 2021 – Present",
                    "Cut latency by 40%",
                    "Backend Engineer — Globex\nJohannesburg · Jan 2016 – Dec 2020",
                    "Built ETL pipelines",
                    "Projects",
                    "Ledger\nGo, PostgreSQL · https://ledger.example.org",
                    "Double-entry ledger",
                    "Wrote the core",
                    "Certifications",
                    "AWS Certified Developer — Amazon, May 2022, expires May 2025",
                    "Education",
                    "BSc, Computer Science\nUniversity of Stellenbosch · 2012 – 2015",
                    "Languages",
                    "English (Native), isiXhosa");
            assertThat(doc.getTables()).isEmpty();
        }
    }

    @Test
    void docxBulletsUseNumbering() throws IOException {
        try (XWPFDocument doc = docx(CvLayout.layout(cv()), false)) {
            List<String> bullets = new ArrayList<>();
            for (XWPFParagraph p : doc.getParagraphs()) {
                if (p.getNumID() != null) {
                    bullets.add(p.getText());
                }
            }
            assertThat(bullets).containsExactly("Cut latency by 40%", "Built ETL pipelines", "Wrote the core",
                    "AWS Certified Developer — Amazon, May 2022, expires May 2025");
            assertThat(doc.getParagraphs().stream().filter(p -> p.getNumID() != null).map(XWPFParagraph::getNumID)
                    .distinct()).as("one numbering definition").hasSize(1);
            assertThat(doc.getNumbering()).isNotNull();
        }
    }

    @Test
    void docxDraftFooterOnlyWhenDraft() throws IOException {
        try (XWPFDocument draft = docx(CvLayout.layout(cv()), true)) {
            assertThat(footers(draft)).contains(Block.Footer.DRAFT_TEXT);
            assertThat(String.join("\n", texts(draft))).doesNotContain("DRAFT");
        }
        try (XWPFDocument approved = docx(CvLayout.layout(cv()), false)) {
            assertThat(footers(approved)).doesNotContain("DRAFT");
        }
    }

    @Test
    void docxCoreProperties() throws IOException {
        try (XWPFDocument doc = docx(CvLayout.layout(cv()), false)) {
            var core = doc.getProperties().getCoreProperties();
            assertThat(core.getTitle()).isEqualTo("CV – " + NAME);
            assertThat(core.getCreator()).isEqualTo(NAME);
            assertThat(core.getCreated().toInstant()).isEqualTo(CREATED);
        }
    }

    @Test
    void docxStripsXmlInvalidCharactersAndKeepsUnicode() throws IOException {
        List<Block> blocks = List.of(new Block.Title(NAME), new Block.Paragraph("Tab\there\u0007bell\u0000end￾"),
                new Block.Paragraph("Name " + UNSUPPORTED + " here"));
        try (XWPFDocument doc = docx(blocks, false)) {
            assertThat(texts(doc)).containsExactly(NAME, "Tab herebellend", "Name " + UNSUPPORTED + " here");
        }
    }

    // ------------------------------------------------------------------ PDF

    private static PDDocument pdf(List<Block> blocks, boolean draft) throws IOException {
        byte[] bytes = pdfWriter().write(blocks, CV_META, draft);
        assertThat(new String(bytes, 0, 5, java.nio.charset.StandardCharsets.US_ASCII)).isEqualTo("%PDF-");
        return Loader.loadPDF(bytes);
    }

    private static String text(PDDocument doc) throws IOException {
        return new PDFTextStripper().getText(doc);
    }

    private static String pageText(PDDocument doc, int page) throws IOException {
        PDFTextStripper stripper = new PDFTextStripper();
        stripper.setStartPage(page);
        stripper.setEndPage(page);
        return stripper.getText(doc);
    }

    @Test
    void pdfTextReadsBackInOrderWithTheUnicodeName() throws IOException {
        try (PDDocument doc = pdf(CvLayout.layout(cv()), false)) {
            assertThat(doc.getNumberOfPages()).isEqualTo(1);
            String text = text(doc);
            assertThat(text).containsSubsequence(NAME, "zoe@example.com", "Backend engineer", "Summary text.", "Skills",
                    "Java, PostgreSQL", "Experience", "Senior Backend Engineer — Acme Ltd",
                    "Cape Town · Mar 2021 – Present", "Cut latency by 40%", "Backend Engineer — Globex", "Projects",
                    "Ledger", "Certifications", "AWS Certified Developer", "Education", "BSc, Computer Science",
                    "Languages", "English (Native), isiXhosa");
            assertThat(text).as("bullets are drawn as •").contains("•");
            assertThat(text).doesNotContain("DRAFT");
        }
    }

    @Test
    void pdfDraftFooterIsOnEveryPage() throws IOException {
        try (PDDocument doc = pdf(CvLayout.layout(longCv()), true)) {
            assertThat(doc.getNumberOfPages()).as("long content runs over").isGreaterThanOrEqualTo(2);
            for (int page = 1; page <= doc.getNumberOfPages(); page++) {
                assertThat(pageText(doc, page)).as("page %d", page).contains(Block.Footer.DRAFT_TEXT);
            }
            String all = text(doc);
            assertThat(all).contains("Point 6 of role 12").containsSubsequence("Engineer 1 — Company 1",
                    "Engineer 12 — Company 12");
        }
        try (PDDocument doc = pdf(CvLayout.layout(longCv()), false)) {
            assertThat(text(doc)).doesNotContain("DRAFT");
        }
    }

    @Test
    void pdfReplacesUnsupportedGlyphsAndStripsControls() throws IOException {
        List<Block> blocks = List.of(new Block.Paragraph("Name " + UNSUPPORTED + " here"),
                new Block.Paragraph("Tab\there\u0007bell\u0000end"),
                new Block.BulletItem("Private " + SUPPLEMENTARY + " point"));
        try (PDDocument doc = pdf(blocks, false)) {
            String text = text(doc);
            assertThat(text).contains("Name ? here", "Tab herebellend", "Private ? point").doesNotContain(UNSUPPORTED);
        }
        // the DOCX keeps the character as written (the reader's font substitutes)
        try (XWPFDocument doc = docx(blocks, false)) {
            assertThat(texts(doc)).contains("Name " + UNSUPPORTED + " here", "Private " + SUPPLEMENTARY + " point");
        }
    }

    @Test
    void pdfDocumentInformationAndLanguage() throws IOException {
        try (PDDocument doc = pdf(CvLayout.layout(cv()), false)) {
            PDDocumentInformation info = doc.getDocumentInformation();
            assertThat(info.getTitle()).isEqualTo("CV – " + NAME);
            assertThat(info.getAuthor()).isEqualTo(NAME);
            assertThat(info.getCreator()).isEqualTo("TalentMatch");
            Calendar created = info.getCreationDate();
            assertThat(created.toInstant()).isEqualTo(CREATED);
            String raw = info.getCOSObject().getString(COSName.CREATION_DATE);
            assertThat(raw).as("written in UTC").startsWith("D:20261019081530").matches(".*(Z|\\+00'?00'?)$");
            assertThat(doc.getDocumentCatalog().getLanguage()).isEqualTo("en-GB");
        }
    }

    @Test
    void pdfFontsAreEmbeddedType0Subsets() throws IOException {
        try (PDDocument doc = pdf(CvLayout.layout(cv()), false)) {
            List<PDFont> fonts = new ArrayList<>();
            for (PDPage page : doc.getPages()) {
                for (COSName name : page.getResources().getFontNames()) {
                    fonts.add(page.getResources().getFont(name));
                }
            }
            assertThat(fonts).as("regular and bold").hasSizeGreaterThanOrEqualTo(2);
            assertThat(fonts).allSatisfy(f -> {
                assertThat(f).isInstanceOf(PDType0Font.class);
                assertThat(f.getName()).matches("[A-Z]{6}\\+.+");
                assertThat(f.isEmbedded()).isTrue();
                assertThat(((PDType0Font) f).getDescendantFont().getFontDescriptor().getFontFile2()).isNotNull();
            });
        }
    }

    @Test
    void pdfOfAnEmptyBlockListHasOnePage() throws IOException {
        try (PDDocument doc = pdf(List.of(), false)) {
            assertThat(doc.getNumberOfPages()).isEqualTo(1);
        }
    }

    @Test
    void wordsWiderThanTheLineAreBrokenNotLost() throws IOException {
        String longWord = "x".repeat(400);
        try (PDDocument doc = pdf(List.of(new Block.Paragraph("start " + longWord + " end")), false)) {
            String text = text(doc).replaceAll("\\s", "");
            assertThat(text).contains("start" + longWord + "end");
        }
    }

    @Test
    void theLetterRendersInBothFormats() throws IOException {
        List<Block> blocks = LetterLayout.layout(letter());
        try (PDDocument doc = pdf(blocks, false)) {
            assertThat(text(doc)).containsSubsequence("zoe@example.com", "19 October 2026", "Hiring team, Initech",
                    "Re: Senior Java Developer", "Dear Hiring Manager,", "First paragraph.", "Second paragraph.",
                    "Kind regards,", NAME);
        }
        try (XWPFDocument doc = docx(blocks, false)) {
            assertThat(texts(doc)).endsWith("Kind regards,", NAME);
        }
    }

    @Test
    void theRealBundledFontsLoad() {
        assumeBundledFonts(); // needs src/main/resources/fonts/NotoSans-{Regular,Bold}.ttf
        byte[] bytes = new PdfWriter().write(CvLayout.layout(cv()), CV_META, true);
        assertThat(bytes).isNotEmpty();
        assertThat(PdfFonts.regular()).isNotEmpty();
        assertThat(PdfFonts.bold()).isNotEmpty();
    }
}
