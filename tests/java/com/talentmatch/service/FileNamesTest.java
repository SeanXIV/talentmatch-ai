package com.talentmatch.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** The shared Content-Disposition file name rules (Phase 4 CV upload, Phase 7 exports). */
class FileNamesTest {

    @Test
    void stripUnsafe() {
        assertThat(FileNames.stripUnsafe(" my \"best\" cv/\\\r\n‮.pdf ")).isEqualTo("my best cv.pdf");
        assertThat(FileNames.stripUnsafe("Müller")).isEqualTo("Müller");
        assertThat(FileNames.stripUnsafe(null)).isEmpty();
    }

    @Test
    void keepEndNeverStartsWithALowSurrogate() {
        assertThat(FileNames.keepEnd("abc.pdf", 10)).isEqualTo("abc.pdf");
        assertThat(FileNames.keepEnd("abcdef.pdf", 5)).isEqualTo("f.pdf");
        String s = "😀x.pdf"; // the pair is chars 0..1
        assertThat(FileNames.keepEnd(s, 6)).isEqualTo("x.pdf");
        assertThat(FileNames.keepEnd(s, 7)).isEqualTo(s);
    }

    @Test
    void keepStartNeverEndsWithAHighSurrogate() {
        assertThat(FileNames.keepStart("abc", 10)).isEqualTo("abc");
        assertThat(FileNames.keepStart("abcdef", 3)).isEqualTo("abc");
        assertThat(FileNames.keepStart("ab😀", 3)).isEqualTo("ab");
        assertThat(FileNames.keepStart("ab😀", 4)).isEqualTo("ab😀");
        assertThat(FileNames.keepStart("abc", 0)).isEmpty();
    }
}
