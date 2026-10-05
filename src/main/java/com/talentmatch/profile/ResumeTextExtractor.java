package com.talentmatch.profile;

import com.talentmatch.config.ProfileConfig;
import java.io.IOException;
import java.text.Normalizer;
import java.time.Duration;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.MemoryUsageSetting;
import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * Reads the text layer of a PDF CV with PDFBox. Scanned (image-only) CVs have no text layer and
 * are rejected with a clear message rather than sent to the model empty; OCR is not supported.
 *
 * <p>Untrusted input, so parsing is bounded: PDFBox may use at most {@value #MAX_PARSER_MEMORY_MB}
 * MB of heap for stream data (the rest spills to temp files), the whole parse runs on the small
 * {@link ProfileConfig#RESUME_PARSER_EXECUTOR} pool with a time limit, text extraction stops
 * after {@value #TEXT_LIMIT_FACTOR} × {@code max-text-chars}, and ANY failure of the parser
 * (PDFBox throws runtime exceptions on malformed files) becomes {@link UnreadableResumeException}.
 * Logs never contain the file's content or the parser's message (which can quote it).
 */
@Component
public class ResumeTextExtractor {

    private static final Logger log = LoggerFactory.getLogger(ResumeTextExtractor.class);

    /** Fewer letters than this means there is no usable text layer (typically a scanned CV). */
    static final int MIN_LETTERS = 50;
    static final int MAX_PAGES = 20;
    static final long MAX_PARSER_MEMORY_MB = 64;
    static final int TEXT_LIMIT_FACTOR = 4;
    static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);

    private static final byte[] PDF_MAGIC = {'%', 'P', 'D', 'F', '-'};
    private static final Pattern CONTROL = Pattern.compile("[\\p{Cntrl}&&[^\\n\\t]]");
    /** Format characters: soft hyphen, zero-width space/joiners, bidi controls, BOM. */
    private static final Pattern FORMAT = Pattern.compile("\\p{Cf}");
    private static final Pattern SPACES = Pattern.compile("[ \\t\\u00A0]+");
    private static final Pattern BLANK_LINES = Pattern.compile("\\n{3,}");

    private final Executor executor;
    private final Duration timeout;
    private final int maxTextChars;

    @Autowired
    public ResumeTextExtractor(@Qualifier(ProfileConfig.RESUME_PARSER_EXECUTOR) Executor executor,
                               ProfileProperties properties) {
        this(executor, DEFAULT_TIMEOUT, properties.maxTextChars());
    }

    /**
     * @param executor     runs the parse (a direct executor such as {@code Runnable::run} disables the timeout)
     * @param timeout      longest a parse may take before the upload is refused
     * @param maxTextChars text sent to the model; extraction stops at {@value #TEXT_LIMIT_FACTOR} times this
     */
    public ResumeTextExtractor(Executor executor, Duration timeout, int maxTextChars) {
        this.executor = executor;
        this.timeout = timeout;
        this.maxTextChars = maxTextChars;
    }

    /** Extracted text and page count. */
    public record ExtractedPdf(String text, int pageCount) {
    }

    /** Thrown when the file is a PDF but its text cannot be used; the message is user-facing. */
    public static class UnreadableResumeException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        UnreadableResumeException(String message) {
            super(message);
        }
    }

    /** Thrown when no parser thread is free (several uploads at once); the client may retry. */
    public static class ParserBusyException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        ParserBusyException() {
            super("Other files are being read right now. Try the upload again in a minute.");
        }
    }

    /** True if the bytes start with {@code %PDF-} (the extension and content type are not trusted). */
    public static boolean looksLikePdf(byte[] bytes) {
        if (bytes == null || bytes.length < PDF_MAGIC.length) {
            return false;
        }
        for (int i = 0; i < PDF_MAGIC.length; i++) {
            if (bytes[i] != PDF_MAGIC[i]) {
                return false;
            }
        }
        return true;
    }

    /**
     * Parses on the parser pool with the time limit.
     *
     * @throws UnreadableResumeException with a user-facing message for any unusable file
     * @throws ParserBusyException       when every parser thread is busy
     */
    public ExtractedPdf extract(byte[] pdf) {
        FutureTask<ExtractedPdf> task = new FutureTask<>(() -> parse(pdf));
        try {
            executor.execute(task);
        } catch (RejectedExecutionException e) {
            throw new ParserBusyException();
        }
        try {
            return task.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            task.cancel(true); // PDFBox may not notice; the pool is small and bounded (PRODUCTION_READINESS)
            log.warn("PDF text extraction exceeded {} and was abandoned", timeout);
            throw new UnreadableResumeException("This PDF took too long to read (it may be damaged or unusually "
                    + "complex). Export your CV as a PDF again and upload that.");
        } catch (ExecutionException e) {
            if (e.getCause() instanceof UnreadableResumeException u) {
                throw u;
            }
            throw unreadable(e.getCause());
        } catch (CancellationException e) {
            throw unreadable(e);
        } catch (InterruptedException e) {
            task.cancel(true);
            Thread.currentThread().interrupt();
            throw unreadable(e);
        }
    }

    /** Parses on the calling thread (no time limit). */
    ExtractedPdf parse(byte[] pdf) {
        try (PDDocument doc = Loader.loadPDF(new RandomAccessReadBuffer(pdf), "", null, null,
                MemoryUsageSetting.setupMixed(MAX_PARSER_MEMORY_MB * 1024 * 1024).streamCache)) {
            if (doc.isEncrypted() && !doc.getCurrentAccessPermission().canExtractContent()) {
                throw new UnreadableResumeException("This PDF is protected against copying text. "
                        + "Export your CV again without protection and upload that file.");
            }
            int pages = doc.getNumberOfPages();
            if (pages == 0) {
                throw new UnreadableResumeException("This PDF has no pages.");
            }
            if (pages > MAX_PAGES) {
                throw new UnreadableResumeException("This PDF has " + pages + " pages; a CV can have at most "
                        + MAX_PAGES + ". Upload just your CV.");
            }
            String text = clean(pageText(doc, pages));
            if (letters(text) < MIN_LETTERS) {
                throw new UnreadableResumeException("No text could be read from this PDF; it looks like a scanned "
                        + "image. Export your CV from your word processor as a PDF (File > Save as PDF) and upload that.");
            }
            return new ExtractedPdf(text, pages);
        } catch (InvalidPasswordException e) {
            throw new UnreadableResumeException("This PDF is password-protected. Remove the password and upload it again.");
        } catch (UnreadableResumeException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            throw unreadable(e);
        }
    }

    /** Page by page, stopping once the text is far longer than the model will ever read. */
    private String pageText(PDDocument doc, int pages) throws IOException {
        long limit = (long) maxTextChars * TEXT_LIMIT_FACTOR;
        PDFTextStripper stripper = new PDFTextStripper();
        stripper.setSortByPosition(true);
        StringBuilder sb = new StringBuilder();
        for (int page = 1; page <= pages && sb.length() < limit; page++) {
            stripper.setStartPage(page);
            stripper.setEndPage(page);
            sb.append(stripper.getText(doc));
        }
        if (sb.length() > limit) {
            sb.setLength((int) limit);
        }
        return sb.toString();
    }

    private static UnreadableResumeException unreadable(Throwable cause) {
        // class name only: parser messages can quote the file's content
        log.info("PDF could not be read: {}", cause == null ? "unknown" : cause.getClass().getSimpleName());
        return new UnreadableResumeException("This file could not be read as a PDF (it may be damaged). "
                + "Export your CV as a PDF again and upload that.");
    }

    /**
     * NFKC (ligatures such as "ﬁ" become "fi", full-width letters become ASCII), format
     * characters removed (soft hyphens, zero-width spaces, bidi controls), control characters
     * removed, spaces collapsed, at most one blank line.
     */
    static String clean(String raw) {
        String t = Normalizer.normalize(raw, Normalizer.Form.NFKC);
        t = FORMAT.matcher(t).replaceAll("");
        t = t.replace("\r\n", "\n").replace('\r', '\n');
        t = CONTROL.matcher(t).replaceAll("");
        t = SPACES.matcher(t).replaceAll(" ");
        t = t.lines().map(String::strip).reduce((a, b) -> a + "\n" + b).orElse("");
        return BLANK_LINES.matcher(t).replaceAll("\n\n").strip();
    }

    private static int letters(String text) {
        int n = 0;
        for (int i = 0; i < text.length(); i++) {
            if (Character.isLetter(text.charAt(i))) {
                n++;
            }
        }
        return n;
    }
}
