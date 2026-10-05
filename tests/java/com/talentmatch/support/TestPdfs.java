package com.talentmatch.support;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;

/** PDFs generated in memory with PDFBox for the Phase 4 CV tests. */
public final class TestPdfs {

    /** The CV text that {@link FakeExtractionModel#DEFAULT_JSON} is grounded in. */
    public static final List<String> CV_LINES = List.of(
            "Ada Lovelace",
            "Backend Engineer - London - ada@example.com",
            "Experience",
            "Backend Engineer, Acme Ltd, 2019-03 - Present",
            "Built payment APIs in Java and PostgreSQL",
            "Skills: Java, PostgreSQL, Docker",
            "Education",
            "University of London, BSc Mathematics, 2012 - 2015");

    public static final String CV_TEXT = String.join("\n", CV_LINES);

    /** A DejaVu font that can draw ligature code points; null if the machine has none. */
    public static final File UNICODE_FONT = Arrays.stream(new String[] {
                    "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf", "C:/Windows/Fonts/arial.ttf"})
            .map(File::new).filter(File::isFile).findFirst().orElse(null);

    private TestPdfs() {
    }

    /** One page with {@link #CV_TEXT}. */
    public static byte[] cv() {
        return text(CV_LINES);
    }

    /** One page per element of {@code pages}; each page holds the given lines. */
    public static byte[] text(List<String> lines) {
        return pages(List.of(lines));
    }

    public static byte[] pages(List<List<String>> pages) {
        try (PDDocument doc = new PDDocument()) {
            PDFont font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            for (List<String> lines : pages) {
                addPage(doc, font, lines);
            }
            return save(doc);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** {@code n} pages of readable CV text. */
    public static byte[] manyPages(int n) {
        List<List<String>> pages = new java.util.ArrayList<>();
        for (int i = 0; i < n; i++) {
            pages.add(CV_LINES);
        }
        return pages(pages);
    }

    /** One page without any text (like a scanned CV). */
    public static byte[] blank() {
        return pages(List.of(List.of()));
    }

    /** Needs {@code userPassword} to open at all. */
    public static byte[] userPassword(String userPassword) {
        return encrypted(userPassword, new AccessPermission());
    }

    /** Opens without a password but forbids text extraction. */
    public static byte[] noCopy() {
        AccessPermission p = new AccessPermission();
        p.setCanExtractContent(false);
        p.setCanExtractForAccessibility(false);
        return encrypted("", p);
    }

    private static byte[] encrypted(String userPassword, AccessPermission permission) {
        try (PDDocument doc = new PDDocument()) {
            addPage(doc, new PDType1Font(Standard14Fonts.FontName.HELVETICA), CV_LINES);
            StandardProtectionPolicy policy = new StandardProtectionPolicy("owner-secret", userPassword, permission);
            policy.setEncryptionKeyLength(128);
            doc.protect(policy);
            return save(doc);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** CV text drawn with real ligature and soft-hyphen code points (needs {@link #UNICODE_FONT}). */
    public static byte[] ligatures() {
        try (PDDocument doc = new PDDocument()) {
            PDFont font = PDType0Font.load(doc, UNICODE_FONT);
            List<String> lines = new java.util.ArrayList<>(CV_LINES);
            lines.add("Certi\uFB01ed Kubernetes Administrator");
            lines.add("Of\uFB01ce automation and work\uFB02ow tooling");
            addPage(doc, font, lines);
            return save(doc);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Starts like a PDF, then garbage. */
    public static byte[] corrupt() {
        return "%PDF-1.7\n1 0 obj <</Type /Catalog /Pages 2 0 R>> endobj\nxref garbage \u0000\u0001 trailer <<>>"
                .getBytes(StandardCharsets.ISO_8859_1);
    }

    /** A valid PDF padded with a trailing comment to at least {@code size} bytes. */
    public static byte[] ofSize(int size) {
        byte[] pdf = cv();
        if (pdf.length >= size) {
            return pdf;
        }
        byte[] out = Arrays.copyOf(pdf, size);
        // trailing bytes after %%EOF: a PDF comment line of spaces
        out[pdf.length] = '\n';
        out[pdf.length + 1] = '%';
        Arrays.fill(out, pdf.length + 2, size, (byte) ' ');
        return out;
    }

    private static void addPage(PDDocument doc, PDFont font, List<String> lines) throws IOException {
        PDPage page = new PDPage();
        doc.addPage(page);
        if (lines.isEmpty()) {
            return;
        }
        try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
            cs.beginText();
            cs.setFont(font, 11);
            cs.setLeading(14);
            cs.newLineAtOffset(50, 740);
            for (String line : lines) {
                cs.showText(line);
                cs.newLine();
            }
            cs.endText();
        }
    }

    private static byte[] save(PDDocument doc) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        doc.save(out);
        return out.toByteArray();
    }
}
