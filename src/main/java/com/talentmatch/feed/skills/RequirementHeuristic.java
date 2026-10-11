package com.talentmatch.feed.skills;

import java.util.regex.Pattern;

/**
 * Required vs nice-to-have for a skill mention in a posting (pure). A mention is nice-to-have when
 * its sentence says so ("Kubernetes is advantageous"), or when the nearest heading above it says
 * so ("Nice to have:", "## Bonus points"). Otherwise it is required. "Advantageous" is the common
 * South African wording.
 */
public final class RequirementHeuristic {

    /** Words that mark a skill as optional. */
    static final Pattern NICE = Pattern.compile("(?iu)\\b(?:nice[ -]to[ -]haves?|bonus|preferred|advantageous|"
            + "(?:an|a big|a definite) advantage|a plus|desirable|would be great)\\b");

    /** A heading is a short line; longer lines are prose. */
    private static final int MAX_HEADING_LENGTH = 80;

    /** A colon-less heading has at most this many words ("Would be great if you have" is six). */
    private static final int MAX_BARE_HEADING_WORDS = 6;

    /**
     * List-item markers: an ASCII marker or a number followed by whitespace ("- Java", "* Bonus",
     * "1. SQL"), or a typographic bullet with or without a space ("• Preferred"). Markdown bold
     * ("**Requirements**") is not a bullet because no whitespace follows the asterisk.
     */
    private static final Pattern BULLET = Pattern.compile("^(?:[•●▪◦·‣]|[-*+–—]\\s|\\d{1,2}[.)]\\s)");

    /**
     * Phrases that open a colon-less section heading, nice-to-have or required ("Bonus points",
     * "NICE TO HAVE", "Requirements", "What you'll need", "Preferred qualifications"). Generic words
     * that also open ordinary list lines ("Experience with Docker", "Skills in SQL") are not here;
     * they count only as a whole line, see {@link #BARE_HEADING_WHOLE}.
     */
    private static final Pattern BARE_HEADING_START = Pattern.compile("(?iu)^(?:"
            + "nice[ -]to[ -]haves?|bonus(?:es)?|preferred|advantageous|desirable|would be (?:great|nice)|"
            + "requirements?|required|must[ -]haves?|essential (?:skills|requirements)|"
            + "(?:minimum|basic) (?:requirements|qualifications)|qualifications?|responsibilities|"
            + "key responsibilities|what (?:you(?:'|’)?ll|you will|you) (?:need|bring|have|do)|"
            + "what we(?:'|’)?re looking for|what we are looking for|who you are|about you)\\b");

    /** Generic headings that count only when they are the whole line ("Skills", "Experience"). */
    private static final Pattern BARE_HEADING_WHOLE = Pattern.compile("(?iu)^(?:your |the |required |key )?"
            + "(?:skills|experience|skills (?:and|&) experience|experience (?:and|&) skills|tech stack|"
            + "technologies|tools|the role|the ideal candidate)$");

    private RequirementHeuristic() {
    }

    /** True if the mention starting at {@code offset} in {@code text} is nice-to-have. */
    public static boolean niceToHave(String text, int offset) {
        if (text == null || offset < 0 || offset > text.length()) {
            return false;
        }
        int start = sentenceStart(text, offset);
        int end = sentenceEnd(text, offset);
        if (NICE.matcher(text.substring(start, end)).find()) {
            return true;
        }
        return nearestHeadingIsNice(text, offset);
    }

    /** Scans the lines above the mention's line; the nearest heading decides. */
    private static boolean nearestHeadingIsNice(String text, int offset) {
        int lineStart = text.lastIndexOf('\n', Math.max(0, offset - 1)) + 1;
        int cursor = lineStart - 1;                 // the '\n' that ends the previous line
        while (cursor > 0) {
            int prevStart = text.lastIndexOf('\n', cursor - 1) + 1;
            String line = text.substring(prevStart, cursor).strip();
            if (isHeading(line)) {
                return NICE.matcher(line).find();
            }
            cursor = prevStart - 1;
        }
        return false;
    }

    /**
     * A heading is a short (trimmed) line that is not a list item and either
     * <ul>
     *   <li>ends with ':' ("Nice to have:", "Requirements:"),</li>
     *   <li>is a markdown heading ("## Bonus points"), or</li>
     *   <li>is a colon-less heading: at most six words, no sentence-ending punctuation, and it starts
     *       with a known section phrase ("Bonus points", "Advantageous", "NICE TO HAVE",
     *       "Requirements", "What you'll need") or is a generic heading on its own ("Skills").</li>
     * </ul>
     * Any heading starts a new section, nice or not. Other plain lines are list items or prose,
     * whatever words they contain ("Experience with Docker", "Docker is a plus").
     */
    static boolean isHeading(String line) {
        if (line == null || line.isEmpty() || line.length() > MAX_HEADING_LENGTH) {
            return false;
        }
        if (BULLET.matcher(line).find()) {
            return false;
        }
        if (line.endsWith(":") || line.startsWith("#")) {
            return true;
        }
        String bare = stripEmphasis(line);
        if (bare.isEmpty() || endsLikeProse(bare) || wordCount(bare) > MAX_BARE_HEADING_WORDS) {
            return false;
        }
        return BARE_HEADING_START.matcher(bare).find() || BARE_HEADING_WHOLE.matcher(bare).matches();
    }

    /** "**Nice to have**" → "Nice to have". */
    private static String stripEmphasis(String line) {
        int start = 0;
        int end = line.length();
        while (start < end && (line.charAt(start) == '*' || line.charAt(start) == '_')) {
            start++;
        }
        while (end > start && (line.charAt(end - 1) == '*' || line.charAt(end - 1) == '_')) {
            end--;
        }
        return line.substring(start, end).strip();
    }

    private static boolean endsLikeProse(String line) {
        char last = line.charAt(line.length() - 1);
        return isTerminator(last) || last == ',' || last == ';';
    }

    private static int wordCount(String line) {
        return line.split("\\s+").length;
    }

    /** Start of the sentence holding {@code offset}: after the previous line break or ". ", "! ", "? ". */
    static int sentenceStart(String text, int offset) {
        for (int i = Math.min(offset, text.length()) - 1; i >= 0; i--) {
            char c = text.charAt(i);
            if (c == '\n') {
                return i + 1;
            }
            if (Character.isWhitespace(c) && i > 0 && isTerminator(text.charAt(i - 1))) {
                return i + 1;
            }
        }
        return 0;
    }

    /** End (exclusive) of the sentence holding {@code offset}. */
    static int sentenceEnd(String text, int offset) {
        for (int i = offset; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\n') {
                return i;
            }
            if (isTerminator(c) && (i + 1 == text.length() || Character.isWhitespace(text.charAt(i + 1)))) {
                return i + 1;
            }
        }
        return text.length();
    }

    private static boolean isTerminator(char c) {
        return c == '.' || c == '!' || c == '?';
    }
}
