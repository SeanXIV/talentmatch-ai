package com.talentmatch.application.render;

import static com.talentmatch.application.render.RenderFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.talentmatch.application.content.DocumentKind;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;

/** §9.1.10/11: the renderer end to end, file names (unsafe characters, -draft, the 150 cap) and formats. */
class DocumentRendererTest {

    private static DocumentRenderer renderer() {
        return new DocumentRenderer(new DocxWriter(), pdfWriter());
    }

    @Test
    void rendersADraftCvPdf() throws IOException {
        RenderedFile f = renderer().render(cv(), "Acme", CREATED, ExportFormat.PDF, true);
        assertThat(f.contentType()).isEqualTo("application/pdf");
        assertThat(f.fileName()).isEqualTo("CV - Zoë Ndlovu-Müller - Acme-draft.pdf");
        try (PDDocument doc = Loader.loadPDF(f.bytes())) {
            assertThat(new PDFTextStripper().getText(doc)).contains(NAME, Block.Footer.DRAFT_TEXT);
            assertThat(doc.getDocumentInformation().getTitle()).isEqualTo("CV – " + NAME);
        }
    }

    @Test
    void rendersAnApprovedLetterDocx() throws IOException {
        Object content = letter();
        RenderedFile f = renderer().render(content, "Initech", CREATED, ExportFormat.DOCX, false);
        assertThat(f.contentType())
                .isEqualTo("application/vnd.openxmlformats-officedocument.wordprocessingml.document");
        assertThat(f.fileName()).isEqualTo("Cover letter - Zoë Ndlovu-Müller - Initech.docx");
        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(f.bytes()))) {
            assertThat(doc.getProperties().getCoreProperties().getTitle()).isEqualTo("Cover letter – " + NAME);
            assertThat(doc.getFooterList()).isEmpty();
            assertThat(f.bytes().length).isLessThan(DocumentRenderer.MAX_BYTES);
        }
    }

    @Test
    void otherContentIsRejected() {
        assertThatThrownBy(() -> renderer().render("text", "Acme", CREATED, ExportFormat.PDF, false))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Cannot render String");
        assertThatThrownBy(() -> renderer().render((Object) null, "Acme", CREATED, ExportFormat.PDF, false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DocumentRenderer(new DocxWriter(), new PdfWriter())
                .render(cv(), "Acme", CREATED, null, false)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void titles() {
        assertThat(DocumentRenderer.title(DocumentKind.CV, NAME)).isEqualTo("CV – " + NAME);
        assertThat(DocumentRenderer.title(DocumentKind.COVER_LETTER, "  ")).isEqualTo("Cover letter");
        assertThat(DocumentRenderer.title(DocumentKind.CV, null)).isEqualTo("CV");
        assertThat(DocumentRenderer.kindLabel(DocumentKind.COVER_LETTER)).isEqualTo("Cover letter");
    }

    // ------------------------------------------------------------------ file names

    private static String name(String fullName, String company, boolean draft, ExportFormat format) {
        return DocumentRenderer.fileName(DocumentKind.CV, fullName, company, draft, format);
    }

    @Test
    void fileNamePatterns() {
        assertThat(name(NAME, "Acme", true, ExportFormat.PDF)).isEqualTo("CV - Zoë Ndlovu-Müller - Acme-draft.pdf");
        assertThat(name(NAME, "Acme", false, ExportFormat.DOCX)).isEqualTo("CV - Zoë Ndlovu-Müller - Acme.docx");
        assertThat(DocumentRenderer.fileName(DocumentKind.COVER_LETTER, NAME, "Acme", false, ExportFormat.PDF))
                .isEqualTo("Cover letter - Zoë Ndlovu-Müller - Acme.pdf");
        assertThat(name(NAME, " ", false, ExportFormat.PDF)).isEqualTo("CV - Zoë Ndlovu-Müller.pdf");
        assertThat(name(null, null, true, ExportFormat.PDF)).isEqualTo("CV-draft.pdf");
    }

    @Test
    void unsafeCharactersAreRemoved() {
        assertThat(name("Zoë \"Z\" Ndlovu/Müller\\", "Acme‮ fdp.exe", false, ExportFormat.PDF))
                .isEqualTo("CV - Zoë Z NdlovuMüller - Acme fdp.exe.pdf");
        assertThat(name("Zoë\r\nX-Evil: 1", "Ac\u0000me​", false, ExportFormat.PDF))
                .isEqualTo("CV - Zoë X-Evil: 1 - Acme.pdf");
        assertThat(name("Zoë", "\"/\\", false, ExportFormat.DOCX)).isEqualTo("CV - Zoë.docx");
    }

    @Test
    void theNameIsCappedAt150AndKeepsTheSuffix() {
        String f = name(NAME, "C".repeat(300), true, ExportFormat.PDF);
        assertThat(f).hasSize(150).startsWith("CV - Zoë Ndlovu-Müller - CCC").endsWith("C-draft.pdf");
        String g = name(NAME, "C".repeat(300), false, ExportFormat.DOCX);
        assertThat(g).hasSize(150).endsWith("C.docx");
        assertThat(name(NAME, "C".repeat(100), true, ExportFormat.PDF)).hasSizeLessThan(150)
                .endsWith("C".repeat(100) + "-draft.pdf");
    }

    @Test
    void theCapNeverSplitsASurrogatePair() {
        // stem limit 150 - "-draft.pdf" = 140; "CV - " is 5, so 134 letters put an emoji's high surrogate at 139
        String fullName = "A".repeat(134) + "😀😀";
        String f = name(fullName, "Acme", true, ExportFormat.PDF);
        assertThat(f).hasSizeLessThanOrEqualTo(150).endsWith("A-draft.pdf");
        for (int i = 0; i < f.length(); i++) {
            char c = f.charAt(i);
            if (Character.isHighSurrogate(c)) {
                assertThat(Character.isLowSurrogate(f.charAt(i + 1))).isTrue();
            }
            if (Character.isLowSurrogate(c)) {
                assertThat(i > 0 && Character.isHighSurrogate(f.charAt(i - 1))).isTrue();
            }
        }
        String fits = name("A".repeat(133) + "😀", "Acme", true, ExportFormat.PDF);
        assertThat(fits).endsWith("😀-draft.pdf").hasSize(150);
    }

    @Test
    void theCutNeverEndsOnADanglingSeparator() {
        // "CV - " + 133 letters = 138 characters; the " - " separator would start at 138
        String f = name("A".repeat(133), "Acme", true, ExportFormat.PDF);
        assertThat(f).isEqualTo("CV - " + "A".repeat(133) + "-draft.pdf");
    }

    // ------------------------------------------------------------------ formats

    @Test
    void exportFormatParsing() {
        assertThat(ExportFormat.parse("pdf")).contains(ExportFormat.PDF);
        assertThat(ExportFormat.parse("PDF")).contains(ExportFormat.PDF);
        assertThat(ExportFormat.parse(" Docx ")).contains(ExportFormat.DOCX);
        assertThat(ExportFormat.parse("doc")).isEmpty();
        assertThat(ExportFormat.parse("pdf2")).isEmpty();
        assertThat(ExportFormat.parse("")).isEmpty();
        assertThat(ExportFormat.parse(null)).isEmpty();
        assertThat(ExportFormat.PDF.extension()).isEqualTo("pdf");
        assertThat(ExportFormat.DOCX.extension()).isEqualTo("docx");
        assertThat(ExportFormat.values()).extracting(Enum::name).containsExactly("PDF", "DOCX");
    }

    @Test
    void renderTextClean() {
        assertThat(RenderText.clean(" a\tb\n c\u0007​﷐d￿ ")).isEqualTo("a b cd");
        assertThat(RenderText.clean("é")).as("NFC").isEqualTo("é");
        assertThat(RenderText.clean("lone \uD800 surrogate")).isEqualTo("lone surrogate");
        assertThat(RenderText.clean(null)).isEmpty();
        assertThat(RenderText.join(", ", " a ", null, " ", "b")).isEqualTo("a, b");
        assertThat(RenderText.join(", ", (String) null)).isNull();
    }
}
