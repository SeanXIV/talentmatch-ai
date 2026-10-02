package com.talentmatch.service;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * String normalization identical in intent to the ETL ({@code scripts/etl/talentmatch_etl/cleaning.py}):
 * trim surrounding whitespace and map empty to null; emails are also lowercased; skill names
 * also collapse internal whitespace runs to one space.
 */
public final class TextNormalizer {

    /** Same shape check as the ETL: local@domain.tld without whitespace. */
    private static final Pattern EMAIL = Pattern.compile("[^@\\s]+@[^@\\s]+\\.[^@\\s]+",
            Pattern.UNICODE_CHARACTER_CLASS);
    private static final Pattern WHITESPACE_RUN = Pattern.compile("\\s+", Pattern.UNICODE_CHARACTER_CLASS);

    public static final int MAX_EMAIL_LENGTH = 320;

    private TextNormalizer() {
    }

    /** Strip surrounding whitespace; empty becomes null. */
    public static String text(String s) {
        if (s == null) {
            return null;
        }
        String stripped = s.strip();
        return stripped.isEmpty() ? null : stripped;
    }

    /** Strip and lowercase; empty becomes null. */
    public static String email(String s) {
        String t = text(s);
        return t == null ? null : t.toLowerCase(Locale.ROOT);
    }

    /** Strip and collapse internal whitespace runs to one space; empty becomes null. */
    public static String skillName(String s) {
        String t = text(s);
        return t == null ? null : WHITESPACE_RUN.matcher(t).replaceAll(" ");
    }

    /** Case-insensitive identity of a normalized skill name. */
    public static String skillKey(String normalizedName) {
        return normalizedName.toLowerCase(Locale.ROOT);
    }

    /** Basic shape check: local@domain.tld, no whitespace, at most 320 characters. */
    public static boolean isValidEmail(String normalizedEmail) {
        return normalizedEmail != null
                && normalizedEmail.length() <= MAX_EMAIL_LENGTH
                && EMAIL.matcher(normalizedEmail).matches();
    }
}
