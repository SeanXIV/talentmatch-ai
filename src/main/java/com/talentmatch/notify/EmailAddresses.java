package com.talentmatch.notify;

/**
 * Masking of email addresses for API responses and {@code toString} (addresses are PII). Pure.
 */
public final class EmailAddresses {

    static final String MASK = "•••";

    private EmailAddresses() {
    }

    /**
     * {@code owner@example.com} → {@code o•••@example.com}. A personal-name form
     * ({@code "Owner" <owner@example.com>}) is reduced to its address first, so the name never shows.
     * Blank → null; no {@code @} → {@code •••}.
     */
    public static String mask(String address) {
        if (address == null || address.isBlank()) {
            return null;
        }
        String a = address.strip();
        int lt = a.lastIndexOf('<');
        int gt = a.lastIndexOf('>');
        if (lt >= 0 && gt > lt) {
            a = a.substring(lt + 1, gt).strip();
        }
        int at = a.lastIndexOf('@');
        if (at < 0) {
            return MASK;
        }
        String domain = a.substring(at + 1);
        if (at == 0) {
            return MASK + "@" + domain;
        }
        return a.substring(0, a.offsetByCodePoints(0, 1)) + MASK + "@" + domain;
    }

    /** The domain of an address (personal-name form allowed), lower-cased; null when there is none. */
    public static String domain(String address) {
        if (address == null) {
            return null;
        }
        String a = address.strip();
        int lt = a.lastIndexOf('<');
        int gt = a.lastIndexOf('>');
        if (lt >= 0 && gt > lt) {
            a = a.substring(lt + 1, gt).strip();
        }
        int at = a.lastIndexOf('@');
        if (at < 0 || at == a.length() - 1) {
            return null;
        }
        return a.substring(at + 1).toLowerCase(java.util.Locale.ROOT);
    }
}
