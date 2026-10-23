package com.talentmatch.ai;

import java.util.regex.Pattern;

/**
 * Prompt hygiene for untrusted text interpolated into a user message (pure, thread-safe). Shared by
 * the match explanations ({@link ExplanationPromptBuilder}) and the Phase 7 tailoring prompts.
 *
 * <p>{@code & < >} are escaped, {@code {{ / }}} are broken up (LangChain4j treats the message as a
 * template), and control characters other than newline are removed. Single-line fields also have
 * whitespace collapsed. The output of these methods is part of the explanation input hashes, so it
 * must never change.
 */
public final class PromptText {

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final String ELLIPSIS = "…";

    private PromptText() {
    }

    /** Single-line field: sanitized with whitespace (including newlines) collapsed to one space. */
    public static String line(String text) {
        if (text == null) {
            return "";
        }
        return WHITESPACE.matcher(sanitize(text)).replaceAll(" ").strip();
    }

    /**
     * Escapes {@code & < >}, breaks up {@code {{ }}}, normalizes line breaks and tabs, and strips
     * every other control character.
     */
    public static String sanitize(String text) {
        if (text == null) {
            return "";
        }
        String s = text.replace("\r\n", "\n").replace('\r', '\n').replace('\t', ' ');
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\n') {
                sb.append(c);
            } else if (Character.isISOControl(c)) {
                continue;
            } else if (c == '&') {
                sb.append("&amp;");
            } else if (c == '<') {
                sb.append("&lt;");
            } else if (c == '>') {
                sb.append("&gt;");
            } else {
                sb.append(c);
            }
        }
        String out = sb.toString();
        while (out.contains("{{")) {
            out = out.replace("{{", "{ {");
        }
        while (out.contains("}}")) {
            out = out.replace("}}", "} }");
        }
        return out;
    }

    /**
     * Cuts {@code text} to at most {@code max} characters at the last whitespace at or before
     * the limit (hard cut if there is none) and appends an ellipsis.
     */
    public static String truncate(String text, int max) {
        if (text.length() <= max) {
            return text;
        }
        int cut = -1;
        for (int i = Math.min(max, text.length() - 1); i > 0; i--) {
            if (Character.isWhitespace(text.charAt(i))) {
                cut = i;
                break;
            }
        }
        String head = cut > 0 ? text.substring(0, cut) : text.substring(0, max);
        return head.stripTrailing() + ELLIPSIS;
    }
}
