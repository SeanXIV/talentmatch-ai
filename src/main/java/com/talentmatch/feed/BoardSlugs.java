package com.talentmatch.feed;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Board-name guesses for a company name (step 12, pure). Greenhouse, Lever and Ashby boards are usually
 * named after the company in lower case, either run together ({@code acmepayments}) or hyphenated
 * ({@code acme-payments}); sometimes only the first word is used ({@code acme}).
 * <ol>
 *   <li>Lower case, NFKD, accents dropped ("Café" → "cafe").</li>
 *   <li>Dots and apostrophes are removed as in {@link CompanyNames} ("B.V." → "bv", "Macy's" → "macys");
 *       every other character outside {@code a-z0-9} separates words.</li>
 *   <li>Trailing legal suffixes ({@link CompanyNames#LEGAL_SUFFIXES}) are dropped repeatedly, never the
 *       last remaining word.</li>
 *   <li>Candidates, most specific first: joined, hyphenated (two words or more), first word (two words
 *       or more and at least 3 characters). Only valid board tokens ({@link SourceKeys#isValidToken}),
 *       no duplicates, at most {@code max}.</li>
 * </ol>
 * "Acme Payments (Pty) Ltd" → {@code [acmepayments, acme-payments, acme]}. These are guesses: a board
 * may use another name, and a guessed name may belong to another company.
 */
public final class BoardSlugs {

    /** The shortest first word tried on its own ("at" or "go" would match unrelated boards). */
    static final int MIN_FIRST_TOKEN_LENGTH = 3;

    private static final Pattern MARKS = Pattern.compile("\\p{M}+");
    private static final Pattern REMOVED_MARKS = Pattern.compile("[.'’`´]");
    private static final Pattern SEPARATORS = Pattern.compile("[^a-z0-9]+");

    private BoardSlugs() {
    }

    /**
     * @param companyName the company name as typed (nullable)
     * @param max         the most candidates to return
     * @return the board-name guesses, most specific first; empty when the name has no letters or digits
     *         ({@link #hasLettersOrDigits}) or no guess is a valid board token (e.g. one 101+ char word)
     */
    public static List<String> candidates(String companyName, int max) {
        if (companyName == null || companyName.isBlank() || max <= 0) {
            return List.of();
        }
        List<String> tokens = tokens(companyName);
        if (tokens.isEmpty()) {
            return List.of();
        }
        Set<String> out = new LinkedHashSet<>();
        out.add(String.join("", tokens));
        if (tokens.size() > 1) {
            out.add(String.join("-", tokens));
            if (tokens.get(0).length() >= MIN_FIRST_TOKEN_LENGTH) {
                out.add(tokens.get(0));
            }
        }
        return out.stream().filter(SourceKeys::isValidToken).limit(max).toList();
    }

    /**
     * @param companyName the company name as typed (nullable)
     * @return whether the name has a letter or digit (a–z, 0–9) after normalization, i.e. whether
     *         {@link #candidates} has anything to build from before the board-token check
     */
    public static boolean hasLettersOrDigits(String companyName) {
        return companyName != null && !companyName.isBlank() && !tokens(companyName).isEmpty();
    }

    private static List<String> tokens(String companyName) {
        // Lower-cased before and after NFKD: compatibility forms ("Ｈ", "ℌ") may decompose to upper case.
        String s = Normalizer.normalize(companyName.toLowerCase(Locale.ROOT), Normalizer.Form.NFKD);
        s = MARKS.matcher(s).replaceAll("").toLowerCase(Locale.ROOT);
        s = REMOVED_MARKS.matcher(s).replaceAll("");
        s = SEPARATORS.matcher(s).replaceAll(" ").strip();
        if (s.isEmpty()) {
            return List.of();
        }
        List<String> tokens = new ArrayList<>(List.of(s.split(" ")));
        while (tokens.size() > 1 && CompanyNames.LEGAL_SUFFIXES.contains(tokens.get(tokens.size() - 1))) {
            tokens.remove(tokens.size() - 1);
        }
        return tokens;
    }
}
