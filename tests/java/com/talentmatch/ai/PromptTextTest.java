package com.talentmatch.ai;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Prompt hygiene shared by the explanations and the tailoring prompts (byte-stable: part of input hashes). */
class PromptTextTest {

    @Test
    void sanitizeEscapesTagsBreaksTemplatesAndDropsControls() {
        assertThat(PromptText.sanitize("<a href> & {{x}} }}}\u0007\r\nnext\rline\tTab"))
                .isEqualTo("&lt;a href&gt; &amp; { {x} } } } }\nnext\nline Tab");
        assertThat(PromptText.sanitize("{{{{")).doesNotContain("{{");
        assertThat(PromptText.sanitize(null)).isEmpty();
    }

    @Test
    void lineCollapsesWhitespace() {
        assertThat(PromptText.line("  two\n\nlines \t here  ")).isEqualTo("two lines here");
        assertThat(PromptText.line(null)).isEmpty();
    }

    @Test
    void truncateCutsAtWhitespaceAndAddsAnEllipsis() {
        assertThat(PromptText.truncate("short", 10)).isEqualTo("short");
        assertThat(PromptText.truncate("hello wonderful world", 12)).isEqualTo("hello…");
        assertThat(PromptText.truncate("abcdefghijkl", 5)).isEqualTo("abcde…");
    }
}
