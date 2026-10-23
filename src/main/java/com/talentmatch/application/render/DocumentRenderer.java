package com.talentmatch.application.render;

import com.talentmatch.application.content.CoverLetterContent;
import com.talentmatch.application.content.DocumentKind;
import com.talentmatch.application.content.Header;
import com.talentmatch.application.content.TailoredCvContent;
import com.talentmatch.service.FileNames;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * Renders a document version as PDF or DOCX (§4.8): layout → blocks → writer. Used once per format at
 * approval (no draft footer; the bytes are stored) and on downloads of unapproved versions
 * ({@code draft = true}). Stateless apart from the PDF glyph cache; thread-safe.
 */
@Component
public class DocumentRenderer {

    /** {@code application_document_file.size_bytes} CHECK. */
    public static final int MAX_BYTES = 5 * 1024 * 1024;
    public static final int MAX_FILE_NAME_LENGTH = 150;
    public static final String DRAFT_SUFFIX = "-draft";

    private static final String SEPARATOR = " - ";

    private final DocxWriter docx;
    private final PdfWriter pdf;

    public DocumentRenderer() {
        this(new DocxWriter(), new PdfWriter());
    }

    DocumentRenderer(DocxWriter docx, PdfWriter pdf) {
        this.docx = docx;
        this.pdf = pdf;
    }

    /**
     * @param content   a {@link TailoredCvContent} or a {@link CoverLetterContent}
     * @param company   the application's job company snapshot (for the file name; the CV has none)
     * @param createdAt the version's {@code created_at} (document creation date)
     * @param format    PDF or DOCX
     * @param draft     true for an unapproved version: DRAFT footer and "-draft" file name
     * @throws IllegalArgumentException for any other content type
     * @throws IllegalStateException    if the file would exceed {@link #MAX_BYTES}
     */
    public RenderedFile render(Object content, String company, Instant createdAt, ExportFormat format,
                               boolean draft) {
        if (content instanceof TailoredCvContent cv) {
            return render(cv, company, createdAt, format, draft);
        }
        if (content instanceof CoverLetterContent letter) {
            return render(letter, company, createdAt, format, draft);
        }
        throw new IllegalArgumentException("Cannot render "
                + (content == null ? "null" : content.getClass().getSimpleName()));
    }

    public RenderedFile render(TailoredCvContent cv, String company, Instant createdAt, ExportFormat format,
                               boolean draft) {
        return render(DocumentKind.CV, CvLayout.layout(cv), fullName(cv.header()), company, createdAt, format,
                draft);
    }

    public RenderedFile render(CoverLetterContent letter, String company, Instant createdAt, ExportFormat format,
                               boolean draft) {
        return render(DocumentKind.COVER_LETTER, LetterLayout.layout(letter), fullName(letter.header()), company,
                createdAt, format, draft);
    }

    private RenderedFile render(DocumentKind kind, List<Block> blocks, String fullName, String company,
                                Instant createdAt, ExportFormat format, boolean draft) {
        Objects.requireNonNull(format, "format");
        Objects.requireNonNull(createdAt, "createdAt");
        DocumentMeta meta = new DocumentMeta(title(kind, fullName), fullName, createdAt);
        byte[] bytes = switch (format) {
            case PDF -> pdf.write(blocks, meta, draft);
            case DOCX -> docx.write(blocks, meta, draft);
        };
        if (bytes.length == 0 || bytes.length > MAX_BYTES) {
            // Impossible for this content, but it would violate the V7 size CHECK: fail loudly (500).
            throw new IllegalStateException("Rendered " + format + " is " + bytes.length + " bytes; the limit is "
                    + MAX_BYTES);
        }
        return new RenderedFile(bytes, format.contentType(), fileName(kind, fullName, company, draft, format));
    }

    /** "CV" / "Cover letter". */
    public static String kindLabel(DocumentKind kind) {
        return kind == DocumentKind.CV ? "CV" : "Cover letter";
    }

    /** "CV – {name}" / "Cover letter – {name}"; just the label when the name is blank. */
    public static String title(DocumentKind kind, String fullName) {
        String name = RenderText.clean(fullName);
        return name.isEmpty() ? kindLabel(kind) : kindLabel(kind) + " – " + name;
    }

    /**
     * {@code "{kind label} - {fullName} - {company}{-draft}.{ext}"} (§4.8). Each part is cleaned
     * (whitespace collapsed, control and format characters removed) and stripped of
     * {@link FileNames#UNSAFE} characters; blank parts are left out. The stem is cut so the whole name
     * is at most {@link #MAX_FILE_NAME_LENGTH} characters; the suffix and extension are always kept.
     */
    public static String fileName(DocumentKind kind, String fullName, String company, boolean draft,
                                  ExportFormat format) {
        StringBuilder stem = new StringBuilder(kindLabel(kind));
        for (String part : new String[] {fullName, company}) {
            String safe = FileNames.stripUnsafe(RenderText.clean(part));
            if (!safe.isEmpty()) {
                stem.append(SEPARATOR).append(safe);
            }
        }
        String suffix = (draft ? DRAFT_SUFFIX : "") + "." + format.extension();
        String cut = FileNames.keepStart(stem.toString(), MAX_FILE_NAME_LENGTH - suffix.length()).strip();
        while (cut.endsWith("-")) { // never end the stem on a dangling " -" separator
            cut = cut.substring(0, cut.length() - 1).strip();
        }
        return cut + suffix;
    }

    private static String fullName(Header header) {
        return header == null || header.fullName() == null ? "" : header.fullName().strip();
    }
}
