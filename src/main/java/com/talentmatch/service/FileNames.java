package com.talentmatch.service;

import java.util.regex.Pattern;

/**
 * Shared rules for file names sent in {@code Content-Disposition}: the uploaded CV (Phase 4) and the
 * exported tailored documents (Phase 7).
 */
public final class FileNames {

    /** Control and format characters (bidi overrides such as U+202E can disguise "cv.exe"), quotes, slashes. */
    public static final Pattern UNSAFE = Pattern.compile("[\\p{Cntrl}\\p{Cf}\"\\\\/]");

    private FileNames() {
    }

    /** Unsafe characters removed, surrounding whitespace stripped; null becomes "". */
    public static String stripUnsafe(String s) {
        return s == null ? "" : UNSAFE.matcher(s).replaceAll("").strip();
    }

    /** The last {@code max} characters (keeps the extension); never starts with half of a surrogate pair. */
    public static String keepEnd(String name, int max) {
        if (name.length() <= max) {
            return name;
        }
        int start = name.length() - max;
        if (Character.isLowSurrogate(name.charAt(start))) {
            start++;
        }
        return name.substring(start);
    }

    /** The first {@code max} characters; never ends with half of a surrogate pair. */
    public static String keepStart(String s, int max) {
        if (s.length() <= max) {
            return s;
        }
        int end = Math.max(0, max);
        if (end > 0 && Character.isHighSurrogate(s.charAt(end - 1))) {
            end--;
        }
        return s.substring(0, end);
    }
}
