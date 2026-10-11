package com.talentmatch.feed.source;

import java.util.Set;
import java.util.regex.Pattern;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jsoup.parser.Parser;
import org.jsoup.select.NodeTraversor;
import org.jsoup.select.NodeVisitor;

/**
 * HTML to plain text with line structure (decision l, jsoup), pure. Paragraphs and headings become
 * blank-line separated blocks, list items become {@code "- item"} lines and {@code <br>} a line
 * break, so section headings ("Nice to have") stay on lines of their own for the requirement
 * heuristic. Entities are decoded, {@code &nbsp;} becomes a space, runs of spaces collapse and there
 * is never more than one blank line in a row. Scripts and styles are dropped.
 */
public final class HtmlText {

    /** Blocks separated by a blank line. */
    private static final Set<String> PARAGRAPHS = Set.of("p", "h1", "h2", "h3", "h4", "h5", "h6", "ul", "ol",
            "table", "blockquote", "pre", "dl", "section", "article", "header", "footer");
    /** Blocks that start and end a line. */
    private static final Set<String> LINES = Set.of("div", "tr", "dt", "dd", "hr", "li", "figure", "figcaption",
            "address", "main", "aside", "nav");
    private static final Set<String> DROPPED = Set.of("script", "style", "noscript", "template", "head", "title");

    private static final Pattern SPACES = Pattern.compile("[ \\t\\x0B\\f\\r\\u00A0\\u2007\\u202F]+");

    private HtmlText() {
    }

    /** Decodes HTML entities once ({@code &lt;p&gt;} → {@code <p>}; {@code &amp;nbsp;} → {@code &nbsp;}). */
    public static String unescape(String escaped) {
        return escaped == null ? null : Parser.unescapeEntities(escaped, false);
    }

    /** Text of entity-escaped HTML (Greenhouse job {@code content}): unescape once, then {@link #toText}. */
    public static String escapedToText(String escapedHtml) {
        return toText(unescape(escapedHtml));
    }

    /** Plain text of an HTML fragment; "" for null or blank input. */
    public static String toText(String html) {
        if (html == null || html.isBlank()) {
            return "";
        }
        Element body = Jsoup.parseBodyFragment(html).body();
        body.select(String.join(",", DROPPED)).remove();
        StringBuilder out = new StringBuilder(Math.min(html.length(), 1 << 16));
        NodeTraversor.traverse(new NodeVisitor() {
            @Override
            public void head(Node node, int depth) {
                if (node instanceof TextNode text) {
                    out.append(text.text());
                } else if (node instanceof Element element) {
                    String name = element.normalName();
                    if (name.equals("br")) {
                        out.append('\n');
                    } else if (name.equals("li")) {
                        newLine(out);
                        out.append("- ");
                    } else if (isParagraph(element)) {
                        blankLine(out);
                    } else if (LINES.contains(name)) {
                        newLine(out);
                    }
                }
            }

            @Override
            public void tail(Node node, int depth) {
                if (node instanceof Element element) {
                    if (element.normalName().equals("li") && isBulletOnly(out)) {
                        // An empty item: drop its dangling marker so the next block starts a fresh line.
                        out.setLength(out.lastIndexOf("\n") + 1);
                    }
                    if (isParagraph(element)) {
                        blankLine(out);
                    } else if (LINES.contains(element.normalName())) {
                        newLine(out);
                    }
                }
            }
        }, body);
        return tidy(out);
    }

    /** A paragraph block; a {@code <p>} directly inside a list item is part of that item's line. */
    private static boolean isParagraph(Element element) {
        if (!PARAGRAPHS.contains(element.normalName())) {
            return false;
        }
        Element parent = element.parent();
        return !(element.normalName().equals("p") && parent != null && parent.normalName().equals("li"));
    }

    /** Ends the current line unless it is empty or only holds a list marker. */
    private static void newLine(StringBuilder out) {
        if (!atLineStart(out)) {
            out.append('\n');
        }
    }

    private static void blankLine(StringBuilder out) {
        if (out.length() == 0 || isBulletOnly(out)) {
            return;
        }
        newLine(out);
        out.append('\n');
    }

    private static boolean atLineStart(StringBuilder out) {
        String line = currentLine(out).strip();
        return line.isEmpty() || line.equals("-");
    }

    private static boolean isBulletOnly(StringBuilder out) {
        return currentLine(out).strip().equals("-");
    }

    private static String currentLine(StringBuilder out) {
        return out.substring(out.lastIndexOf("\n") + 1);
    }

    /** Collapses spaces, strips lines, drops empty bullets and repeated blank lines. */
    private static String tidy(CharSequence raw) {
        StringBuilder text = new StringBuilder(raw.length());
        boolean pendingBlank = false;
        for (String line : raw.toString().split("\n", -1)) {
            String clean = SPACES.matcher(line).replaceAll(" ").strip();
            if (clean.isEmpty() || clean.equals("-")) {
                pendingBlank = text.length() > 0;
                continue;
            }
            if (text.length() > 0) {
                text.append(pendingBlank ? "\n\n" : "\n");
            }
            text.append(clean);
            pendingBlank = false;
        }
        return text.toString();
    }
}
