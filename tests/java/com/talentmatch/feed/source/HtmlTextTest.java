package com.talentmatch.feed.source;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** HTML to structured text (decision l): blocks, list items, entities, dropped scripts. */
class HtmlTextTest {

    @Test
    void nullAndBlank() {
        assertThat(HtmlText.toText(null)).isEmpty();
        assertThat(HtmlText.toText("  ")).isEmpty();
        assertThat(HtmlText.unescape(null)).isNull();
    }

    @Test
    void paragraphsHeadingsAndLists() {
        String html = "<h2>About</h2><p>We build things.</p><p><strong>Nice to have</strong></p>"
                + "<ul><li>Kubernetes</li><li><p>Go</p></li></ul><p>Line one<br>Line two</p>";
        assertThat(HtmlText.toText(html)).isEqualTo(
                "About\n\nWe build things.\n\nNice to have\n\n- Kubernetes\n- Go\n\nLine one\nLine two");
    }

    @Test
    void entitiesAndNbspBecomeText() {
        assertThat(HtmlText.toText("<p>R&amp;D&nbsp;&nbsp; team &lt;3</p>")).isEqualTo("R&D team <3");
    }

    @Test
    void unescapeDecodesOnce() {
        assertThat(HtmlText.unescape("&lt;p&gt;A &amp;nbsp; B&lt;/p&gt;")).isEqualTo("<p>A &nbsp; B</p>");
        assertThat(HtmlText.escapedToText("&lt;p&gt;A&amp;nbsp;B &amp;amp; C&lt;/p&gt;")).isEqualTo("A B & C");
    }

    @Test
    void scriptsAndStylesDropped() {
        assertThat(HtmlText.toText("<p>Hi</p><script>alert('x')</script><style>p{}</style><p>there</p>"))
                .isEqualTo("Hi\n\nthere");
    }

    @Test
    void noMoreThanOneBlankLine() {
        assertThat(HtmlText.toText("<p>A</p><p></p><p>&nbsp;</p><div></div><p>B</p>")).isEqualTo("A\n\nB");
    }

    @Test
    void emptyListItemDoesNotTurnTheNextParagraphIntoABullet() {
        assertThat(HtmlText.toText("<ul><li>X</li><li></li></ul><p>B</p>")).isEqualTo("- X\n\nB");
    }

    @Test
    void plainTextPassesThrough() {
        assertThat(HtmlText.toText("Just text,   spaced")).isEqualTo("Just text, spaced");
    }
}
