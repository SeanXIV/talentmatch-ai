package com.talentmatch.application.render;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.GregorianCalendar;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType0Font;

/**
 * Renders blocks as a PDF with PDFBox 3 (§4.8): A4, 2 cm margins, Noto Sans Regular and Bold embedded
 * as subsets (so names such as "Zoë Ndlovu-Müller" render), word wrap by glyph widths, a new page when
 * the cursor passes the bottom margin, headings kept with the next line, bullets as "•" with a hanging
 * indent. Characters the font can't encode become "?" (checked per code point and cached); control
 * characters are stripped. Draft → a footer on every page. No links, annotations or scripts.
 * Thread-safe: all per-document state is local to one {@link #write} call.
 */
public final class PdfWriter {

    private static final PDRectangle PAGE = PDRectangle.A4;
    private static final float MARGIN = 20f * 72f / 25.4f; // 2 cm in points
    private static final float CONTENT_WIDTH = PAGE.getWidth() - 2 * MARGIN;
    private static final float TOP = PAGE.getHeight() - MARGIN;
    private static final float LEADING = 1.3f;
    /** Baseline offset above the bottom of a line box, as a fraction of the font size. */
    private static final float DESCENT = 0.3f;

    private static final float TITLE_SIZE = 18f;
    private static final float HEADING_SIZE = 12f;
    private static final float BODY_SIZE = 10.5f;
    private static final float CONTACT_SIZE = 9.5f;
    private static final float FOOTER_SIZE = 8f;

    private static final float BULLET_INDENT = 12f;
    private static final float BULLET_OFFSET = 2f;
    private static final String BULLET = "•";
    private static final String REPLACEMENT = "?";

    private final Supplier<byte[]> regularTtf;
    private final Supplier<byte[]> boldTtf;
    // Code point → encodable, per font file. Valid across documents: the same TTF bytes every time.
    private final Map<Integer, Boolean> regularGlyphs = new ConcurrentHashMap<>();
    private final Map<Integer, Boolean> boldGlyphs = new ConcurrentHashMap<>();

    /** Uses the bundled Noto Sans fonts ({@code fonts/NotoSans-Regular.ttf}, {@code fonts/NotoSans-Bold.ttf}). */
    public PdfWriter() {
        this(PdfFonts::regular, PdfFonts::bold);
    }

    PdfWriter(Supplier<byte[]> regularTtf, Supplier<byte[]> boldTtf) {
        this.regularTtf = regularTtf;
        this.boldTtf = boldTtf;
    }

    public byte[] write(List<Block> blocks, DocumentMeta meta, boolean draft) {
        try (PDDocument doc = new PDDocument()) {
            Face regular = new Face(PDType0Font.load(doc, new ByteArrayInputStream(regularTtf.get()), true),
                    regularGlyphs);
            Face bold = new Face(PDType0Font.load(doc, new ByteArrayInputStream(boldTtf.get()), true),
                    boldGlyphs);

            List<String> footers = new ArrayList<>();
            Session session = new Session(doc, regular, bold);
            try {
                for (Block block : blocks) {
                    if (block instanceof Block.Footer f) {
                        footers.add(f.text());
                    } else {
                        session.render(block);
                    }
                }
            } finally {
                session.close();
            }
            if (draft) {
                footers.add(Block.Footer.DRAFT_TEXT);
            }
            if (!footers.isEmpty()) {
                drawFooters(doc, regular, footers);
            }
            info(doc, meta);

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not write the PDF", e);
        }
    }

    private static void drawFooters(PDDocument doc, Face face, List<String> footers) throws IOException {
        List<String> lines = new ArrayList<>();
        for (String footer : footers) {
            lines.addAll(wrap(face, FOOTER_SIZE, face.sanitize(RenderText.clean(footer)), CONTENT_WIDTH));
        }
        float leading = FOOTER_SIZE * LEADING;
        for (PDPage page : doc.getPages()) {
            try (PDPageContentStream cs = new PDPageContentStream(doc, page,
                    PDPageContentStream.AppendMode.APPEND, true, true)) {
                cs.setNonStrokingColor(0.4f);
                for (int i = 0; i < lines.size(); i++) {
                    String line = lines.get(i);
                    float baseline = MARGIN / 2 + (lines.size() - 1 - i) * leading;
                    float x = (PAGE.getWidth() - face.width(FOOTER_SIZE, line)) / 2;
                    show(cs, face, FOOTER_SIZE, x, baseline, line);
                }
            }
        }
    }

    private static void info(PDDocument doc, DocumentMeta meta) {
        PDDocumentInformation info = doc.getDocumentInformation();
        info.setTitle(RenderText.clean(meta.title()));
        info.setAuthor(RenderText.clean(meta.author()));
        info.setCreator("TalentMatch");
        GregorianCalendar created = new GregorianCalendar(TimeZone.getTimeZone("UTC"));
        created.setTimeInMillis(meta.created().toEpochMilli());
        info.setCreationDate(created);
        doc.getDocumentCatalog().setLanguage("en-GB");
    }

    private static void show(PDPageContentStream cs, Face face, float size, float x, float baseline, String text)
            throws IOException {
        if (text.isEmpty()) {
            return;
        }
        cs.beginText();
        cs.setFont(face.font(), size);
        cs.newLineAtOffset(x, baseline);
        cs.showText(text);
        cs.endText();
    }

    /**
     * Greedy word wrap by glyph widths. A word wider than the line is broken between code points.
     * The text must already be sanitised for the face (single spaces, encodable characters only).
     */
    static List<String> wrap(Face face, float size, String text, float width) {
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : text.split(" ")) {
            if (word.isEmpty()) {
                continue;
            }
            String candidate = line.length() == 0 ? word : line + " " + word;
            if (face.width(size, candidate) <= width) {
                line.setLength(0);
                line.append(candidate);
                continue;
            }
            if (line.length() > 0) {
                lines.add(line.toString());
                line.setLength(0);
            }
            if (face.width(size, word) <= width) {
                line.append(word);
                continue;
            }
            for (int i = 0; i < word.length(); ) {
                int cp = word.codePointAt(i);
                i += Character.charCount(cp);
                String next = new StringBuilder(line).appendCodePoint(cp).toString();
                if (line.length() > 0 && face.width(size, next) > width) {
                    lines.add(line.toString());
                    line.setLength(0);
                }
                line.appendCodePoint(cp);
            }
        }
        if (line.length() > 0) {
            lines.add(line.toString());
        }
        return lines;
    }

    /** A loaded font plus its shared glyph-coverage cache. */
    record Face(PDType0Font font, Map<Integer, Boolean> glyphs) {

        /** Every code point the font can't encode replaced by "?". */
        String sanitize(String text) {
            StringBuilder out = new StringBuilder(text.length());
            for (int i = 0; i < text.length(); ) {
                int cp = text.codePointAt(i);
                i += Character.charCount(cp);
                if (cp == ' ' || glyphs.computeIfAbsent(cp, this::canEncode)) {
                    out.appendCodePoint(cp);
                } else {
                    out.append(REPLACEMENT);
                }
            }
            return out.toString();
        }

        private boolean canEncode(int cp) {
            try {
                font.encode(new String(Character.toChars(cp)));
                return true;
            } catch (IllegalArgumentException | IOException e) {
                return false; // PDFBox: "No glyph for U+XXXX in font ..."
            }
        }

        float width(float size, String text) {
            try {
                return font.getStringWidth(text) / 1000f * size;
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    /** The cursor over the pages of one document. */
    private static final class Session {

        private final PDDocument doc;
        private final Face regular;
        private final Face bold;
        private PDPageContentStream cs;
        private float y;
        private boolean atTop;

        Session(PDDocument doc, Face regular, Face bold) throws IOException {
            this.doc = doc;
            this.regular = regular;
            this.bold = bold;
            newPage(); // a PDF always has at least one page
        }

        void render(Block block) throws IOException {
            if (block instanceof Block.Title b) {
                text(bold, TITLE_SIZE, b.text(), 0, 2);
            } else if (block instanceof Block.ContactLine b) {
                text(regular, CONTACT_SIZE, b.text(), 2, 6);
            } else if (block instanceof Block.Heading b) {
                heading(b.text());
            } else if (block instanceof Block.Subheading b) {
                subheading(b.text(), b.detail());
            } else if (block instanceof Block.Paragraph b) {
                text(regular, BODY_SIZE, b.text(), 0, 4);
            } else if (block instanceof Block.BulletItem b) {
                bullet(b.text());
            } else if (block instanceof Block.Spacer) {
                space(6);
            }
        }

        private void text(Face face, float size, String raw, float before, float after) throws IOException {
            List<String> lines = lines(face, size, raw, CONTENT_WIDTH);
            if (lines.isEmpty()) {
                return;
            }
            space(before);
            for (String line : lines) {
                line(face, size, MARGIN, line);
            }
            space(after);
        }

        private void heading(String raw) throws IOException {
            List<String> lines = lines(bold, HEADING_SIZE, raw, CONTENT_WIDTH);
            if (lines.isEmpty()) {
                return;
            }
            space(12);
            keepWithNext(lines.size() * HEADING_SIZE * LEADING + 4);
            for (String line : lines) {
                line(bold, HEADING_SIZE, MARGIN, line);
            }
            float ruleY = y - 1.5f;
            cs.setLineWidth(0.5f);
            cs.moveTo(MARGIN, ruleY);
            cs.lineTo(MARGIN + CONTENT_WIDTH, ruleY);
            cs.stroke();
            y -= 4;
        }

        private void subheading(String raw, String rawDetail) throws IOException {
            List<String> lines = lines(bold, BODY_SIZE, raw, CONTENT_WIDTH);
            List<String> detail = lines(regular, BODY_SIZE, rawDetail, CONTENT_WIDTH);
            if (lines.isEmpty() && detail.isEmpty()) {
                return;
            }
            space(5);
            keepWithNext((lines.size() + detail.size()) * BODY_SIZE * LEADING);
            for (String line : lines) {
                line(bold, BODY_SIZE, MARGIN, line);
            }
            for (String line : detail) {
                line(regular, BODY_SIZE, MARGIN, line);
            }
            space(2);
        }

        private void bullet(String raw) throws IOException {
            List<String> lines = lines(regular, BODY_SIZE, raw, CONTENT_WIDTH - BULLET_INDENT);
            if (lines.isEmpty()) {
                return;
            }
            space(1.5f);
            String marker = regular.sanitize(BULLET);
            for (int i = 0; i < lines.size(); i++) {
                float baseline = advance(BODY_SIZE); // may start a new page: read cs only after this
                if (i == 0) {
                    show(cs, regular, BODY_SIZE, MARGIN + BULLET_OFFSET, baseline, marker);
                }
                show(cs, regular, BODY_SIZE, MARGIN + BULLET_INDENT, baseline, lines.get(i));
            }
        }

        /** One line at {@code x}: advances first (possibly to a new page), then draws on the current page. */
        private void line(Face face, float size, float x, String text) throws IOException {
            float baseline = advance(size);
            show(cs, face, size, x, baseline, text);
        }

        private List<String> lines(Face face, float size, String raw, float width) {
            String text = RenderText.clean(raw);
            return text.isEmpty() ? List.of() : wrap(face, size, face.sanitize(text), width);
        }

        /** Moves the cursor down one line (new page if needed) and returns that line's baseline. */
        private float advance(float size) throws IOException {
            float leading = size * LEADING;
            if (!atTop && y - leading < MARGIN) {
                newPage();
            }
            y -= leading;
            atTop = false;
            return y + size * DESCENT;
        }

        /** Starts a new page unless {@code height} plus one body line fits below the cursor. */
        private void keepWithNext(float height) throws IOException {
            if (!atTop && y - height - BODY_SIZE * LEADING < MARGIN) {
                newPage();
            }
        }

        /** Vertical space, dropped at the top of a page. */
        private void space(float points) {
            if (!atTop) {
                y -= points;
            }
        }

        private void newPage() throws IOException {
            if (cs != null) {
                cs.close();
            }
            PDPage page = new PDPage(PAGE);
            doc.addPage(page);
            cs = new PDPageContentStream(doc, page);
            y = TOP;
            atTop = true;
        }

        void close() throws IOException {
            if (cs != null) {
                cs.close();
                cs = null;
            }
        }
    }
}
