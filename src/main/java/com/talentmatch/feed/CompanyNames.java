package com.talentmatch.feed;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Company names compared across sources (§4.4, pure). "Acme (Pty) Ltd", "ACME" and "Acme Inc." all
 * give the key {@code "acme"}.
 * <ol>
 *   <li>NFKC, lower case.</li>
 *   <li>Dots and apostrophes are removed ("B.V." → "bv", "Macy's" → "macys"); every other
 *       punctuation character becomes a space.</li>
 *   <li>Trailing legal suffixes are dropped repeatedly ({@code pty, ltd, limited, inc, llc, gmbh,
 *       plc, corp, corporation, co, bv, sa, ag}), but never the last remaining word.</li>
 *   <li>Whitespace runs collapse to one space.</li>
 * </ol>
 * Known limitation: names that differ beyond the suffixes ("Acme" vs "Acme Payments") stay different.
 */
public final class CompanyNames {

    static final Set<String> LEGAL_SUFFIXES = Set.of("pty", "ltd", "limited", "inc", "llc", "gmbh", "plc", "corp",
            "corporation", "co", "bv", "sa", "ag");

    private static final Pattern REMOVED_MARKS = Pattern.compile("[.'’`´]");
    private static final Pattern PUNCTUATION = Pattern.compile("[^\\p{L}\\p{N}\\s]");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private CompanyNames() {
    }

    /** The comparison key of a company name; "" for null or blank input. */
    public static String key(String company) {
        if (company == null || company.isBlank()) {
            return "";
        }
        String s = Normalizer.normalize(company, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        s = REMOVED_MARKS.matcher(s).replaceAll("");
        s = PUNCTUATION.matcher(s).replaceAll(" ").strip();
        if (s.isEmpty()) {
            return "";
        }
        List<String> tokens = new ArrayList<>(List.of(WHITESPACE.split(s)));
        tokens.removeIf(String::isEmpty);
        while (tokens.size() > 1 && LEGAL_SUFFIXES.contains(tokens.get(tokens.size() - 1))) {
            tokens.remove(tokens.size() - 1);
        }
        return String.join(" ", tokens);
    }
}
