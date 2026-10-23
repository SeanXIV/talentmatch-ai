package com.talentmatch.application.render;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;

/** Text cleaning and joining shared by the layouts and the writers (pure, thread-safe). */
final class RenderText {

    private RenderText() {
    }

    /**
     * NFC-normalised, every whitespace run (including tabs, line breaks and no-break spaces) collapsed
     * to one space, and characters that are invalid in XML 1.0 or invisible removed: control and
     * format characters, unpaired surrogates and the non-characters U+FFFE/U+FFFF (any plane) and
     * U+FDD0..U+FDEF. Null becomes "".
     */
    static String clean(String s) {
        if (s == null || s.isEmpty()) {
            return "";
        }
        String text = Normalizer.normalize(s, Normalizer.Form.NFC);
        StringBuilder out = new StringBuilder(text.length());
        boolean pendingSpace = false;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            if (Character.isWhitespace(cp) || Character.isSpaceChar(cp)) {
                pendingSpace = out.length() > 0;
                continue;
            }
            if (!allowed(cp)) {
                continue;
            }
            if (pendingSpace) {
                out.append(' ');
                pendingSpace = false;
            }
            out.appendCodePoint(cp);
        }
        return out.toString();
    }

    private static boolean allowed(int cp) {
        int type = Character.getType(cp);
        if (type == Character.CONTROL || type == Character.FORMAT || type == Character.SURROGATE) {
            return false;
        }
        return (cp & 0xFFFE) != 0xFFFE && !(cp >= 0xFDD0 && cp <= 0xFDEF);
    }

    /** True when the string has a non-whitespace character. */
    static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }

    /** The non-blank parts, stripped, joined with the separator; null when there are none. */
    static String join(String separator, String... parts) {
        List<String> kept = new ArrayList<>(parts.length);
        for (String part : parts) {
            if (hasText(part)) {
                kept.add(part.strip());
            }
        }
        return kept.isEmpty() ? null : String.join(separator, kept);
    }

    /** The non-blank items, stripped, joined with the separator; null when there are none. */
    static String join(String separator, List<String> items) {
        return items == null ? null : join(separator, items.toArray(String[]::new));
    }
}
