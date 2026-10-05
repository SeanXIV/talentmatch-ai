package com.talentmatch.profile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.talentmatch.profile.ResumeTextExtractor.ExtractedPdf;
import com.talentmatch.profile.ResumeTextExtractor.ParserBusyException;
import com.talentmatch.profile.ResumeTextExtractor.UnreadableResumeException;
import com.talentmatch.support.TestPdfs;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import org.junit.jupiter.api.Test;

/** Phase 4: PDF text extraction with PDFBox (test plan: ResumeTextExtractorTest; S2, S13). */
class ResumeTextExtractorTest {

    private final ResumeTextExtractor extractor = new ResumeTextExtractor(Runnable::run, Duration.ofSeconds(30), 16_000);

    @Test
    void textPdfGivesTextAndPageCount() {
        ExtractedPdf pdf = extractor.extract(TestPdfs.cv());
        assertThat(pdf.pageCount()).isEqualTo(1);
        for (String line : TestPdfs.CV_LINES) {
            assertThat(pdf.text()).contains(line);
        }
        assertThat(extractor.extract(TestPdfs.manyPages(3)).pageCount()).isEqualTo(3);
    }

    @Test
    void blankPageIsUnreadableAsScanned() {
        assertThatThrownBy(() -> extractor.extract(TestPdfs.blank()))
                .isInstanceOf(UnreadableResumeException.class).hasMessageContaining("scanned");
    }

    @Test
    void tooLittleTextIsUnreadable() {
        assertThatThrownBy(() -> extractor.extract(TestPdfs.text(List.of("Ada Lovelace", "CV"))))
                .isInstanceOf(UnreadableResumeException.class);
    }

    @Test
    void userPasswordIsReportedAsPasswordProtected() {
        assertThatThrownBy(() -> extractor.extract(TestPdfs.userPassword("s3cret")))
                .isInstanceOf(UnreadableResumeException.class).hasMessageContaining("password-protected");
    }

    @Test
    void extractionDisallowedIsUnreadable() {
        assertThatThrownBy(() -> extractor.extract(TestPdfs.noCopy()))
                .isInstanceOf(UnreadableResumeException.class).hasMessageContaining("protected against copying");
    }

    @Test
    void twentyPagesOkTwentyOneRefused() {
        assertThat(extractor.extract(TestPdfs.manyPages(20)).pageCount()).isEqualTo(20);
        assertThatThrownBy(() -> extractor.extract(TestPdfs.manyPages(21)))
                .isInstanceOf(UnreadableResumeException.class).hasMessageContaining("21 pages");
    }

    @Test
    void garbageAfterMagicIsUnreadableNeverARuntimeException() {
        for (byte[] bad : List.of(TestPdfs.corrupt(), "%PDF-".getBytes(StandardCharsets.US_ASCII),
                "%PDF-1.4\n%%EOF".getBytes(StandardCharsets.US_ASCII), truncated(TestPdfs.cv()))) {
            Throwable t = catchThrowable(() -> extractor.extract(bad));
            assertThat(t).as("bytes %s", bad.length).isExactlyInstanceOf(UnreadableResumeException.class);
            assertThat(t.getMessage()).doesNotContain("Exception").doesNotContain("pdfbox");
        }
    }

    @Test
    void ligaturesAndFormatCharactersAreNormalized() {
        assumeTrue(TestPdfs.UNICODE_FONT != null, "no TrueType font with ligatures on this machine");
        String text = extractor.extract(TestPdfs.ligatures()).text();
        assertThat(text).contains("Certified Kubernetes Administrator").contains("Office").contains("workflow")
                .doesNotContain("\uFB01").doesNotContain("\uFB02");
    }

    @Test
    void cleanNormalizesWithoutAPdf() {
        assertThat(ResumeTextExtractor.clean("Certi\uFB01ed  Java\u00ADScript\u200B dev\r\n\r\n\r\n\r\nnext\u0007"))
                .isEqualTo("Certified JavaScript dev\n\nnext");
        assertThat(ResumeTextExtractor.clean("\uFF2A\uFF41\uFF56\uFF41")).as("full-width").isEqualTo("Java");
        assertThat(ResumeTextExtractor.clean("cv\u202Efdp.exe")).isEqualTo("cvfdp.exe");
    }

    @Test
    void textIsCappedAtFourTimesMaxTextChars() {
        ResumeTextExtractor small = new ResumeTextExtractor(Runnable::run, Duration.ofSeconds(30), 1000);
        String text = small.extract(TestPdfs.manyPages(20)).text();
        assertThat(text.length()).isLessThanOrEqualTo(4000);
    }

    @Test
    void looksLikePdf() {
        assertThat(ResumeTextExtractor.looksLikePdf(null)).isFalse();
        assertThat(ResumeTextExtractor.looksLikePdf(new byte[0])).isFalse();
        assertThat(ResumeTextExtractor.looksLikePdf("%PDF".getBytes(StandardCharsets.US_ASCII))).isFalse();
        assertThat(ResumeTextExtractor.looksLikePdf("%PDX-1.4".getBytes(StandardCharsets.US_ASCII))).isFalse();
        assertThat(ResumeTextExtractor.looksLikePdf(" %PDF-1.4".getBytes(StandardCharsets.US_ASCII))).isFalse();
        assertThat(ResumeTextExtractor.looksLikePdf("%PDF-".getBytes(StandardCharsets.US_ASCII))).isTrue();
        assertThat(ResumeTextExtractor.looksLikePdf(TestPdfs.cv())).isTrue();
    }

    @Test
    void slowParseTimesOutAsUnreadable() throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        CountDownLatch hold = new CountDownLatch(1);
        try {
            pool.execute(() -> {
                try {
                    hold.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            ResumeTextExtractor timed = new ResumeTextExtractor(pool, Duration.ofMillis(200), 16_000);
            assertThatThrownBy(() -> timed.extract(TestPdfs.cv()))
                    .isInstanceOf(UnreadableResumeException.class).hasMessageContaining("too long");
        } finally {
            hold.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void fullParserPoolIsBusy() {
        ResumeTextExtractor busy = new ResumeTextExtractor(r -> {
            throw new RejectedExecutionException("full");
        }, Duration.ofSeconds(1), 16_000);
        assertThatThrownBy(() -> busy.extract(TestPdfs.cv())).isInstanceOf(ParserBusyException.class);
    }

    private static byte[] truncated(byte[] pdf) {
        return java.util.Arrays.copyOf(pdf, pdf.length / 3);
    }
}
