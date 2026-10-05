package com.talentmatch.profile;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** Phase 4: ResumeService static helpers (file names, hash, sizes). */
class ResumeServiceTest {

    @Test
    void fileNameKeepsLastSegmentAndStripsUnsafeCharacters() {
        assertThat(ResumeService.fileName("C:\\Users\\ada\\Documents\\cv.pdf")).isEqualTo("cv.pdf");
        assertThat(ResumeService.fileName("/home/ada/cv 2026.pdf")).isEqualTo("cv 2026.pdf");
        assertThat(ResumeService.fileName("my \"best\" cv.pdf")).isEqualTo("my best cv.pdf");
        assertThat(ResumeService.fileName("cv\r\nX-Evil: 1.pdf")).isEqualTo("cvX-Evil: 1.pdf");
        assertThat(ResumeService.fileName("cv\u202Efdp.exe")).as("bidi override removed (N4)").isEqualTo("cvfdp.exe");
        assertThat(ResumeService.fileName("Lebenslauf_Müller.pdf")).isEqualTo("Lebenslauf_Müller.pdf");
    }

    @Test
    void emptyOrMissingNameDefaultsToCvPdf() {
        assertThat(ResumeService.fileName(null)).isEqualTo("cv.pdf");
        assertThat(ResumeService.fileName("")).isEqualTo("cv.pdf");
        assertThat(ResumeService.fileName("   ")).isEqualTo("cv.pdf");
        assertThat(ResumeService.fileName("C:\\dir\\")).isEqualTo("cv.pdf");
        assertThat(ResumeService.fileName("\"\"")).isEqualTo("cv.pdf");
    }

    @Test
    void longNameKeepsLast255Characters() {
        String name = "a".repeat(296) + "b.pdf";
        String out = ResumeService.fileName(name);
        assertThat(out).hasSize(255).endsWith("b.pdf");
    }

    @Test
    void longNameNeverSplitsASurrogatePair() {
        String name = "\uD83D\uDE00".repeat(150) + ".pdf"; // 304 chars
        String out = ResumeService.fileName(name);
        assertThat(out.length()).isLessThanOrEqualTo(255);
        assertThat(Character.isLowSurrogate(out.charAt(0))).isFalse();
        assertThat(out).endsWith(".pdf");
    }

    @Test
    void sha256KnownVectors() {
        assertThat(ResumeService.sha256(new byte[0]))
                .isEqualTo("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
        assertThat(ResumeService.sha256("abc".getBytes(StandardCharsets.US_ASCII)))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }

    @Test
    void megabytes() {
        assertThat(ResumeService.megabytes(5_242_880)).isEqualTo("5.0 MB");
        assertThat(ResumeService.megabytes(5_767_168)).isEqualTo("5.5 MB");
        assertThat(ResumeService.megabytes(0)).isEqualTo("0.0 MB");
    }
}
