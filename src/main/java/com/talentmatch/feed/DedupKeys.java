package com.talentmatch.feed;

import com.talentmatch.preferences.Gazetteer;
import com.talentmatch.preferences.Workplace;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The cross-source dedup key of a posting (§4.4, pure):
 * {@code companyKey + "|" + titleKey + "|" + bucket}.
 * <ul>
 *   <li>{@code companyKey}: {@link CompanyNames#key}, at most 200 characters.</li>
 *   <li>{@code titleKey}: NFKC; bracketed parts dropped; a segment after {@code " - "} (or an en/em
 *       dash with spaces) or {@code "|"} that names a place or a work mode ("Remote", "Hybrid",
 *       "South Africa", "EMEA") is dropped together with everything after it; lower case;
 *       {@code sr}/{@code sr.} → senior, {@code jr}/{@code jr.} → junior; punctuation other than
 *       {@code +} and {@code #} becomes a space; whitespace collapsed; at most 300 characters.</li>
 *   <li>{@code bucket}: {@code remote} for a REMOTE posting, otherwise {@code onsite} (deliberately
 *       coarse: cities are written differently by different sources).</li>
 * </ul>
 * So "Sr. Backend Engineer - Remote" and "Senior Backend Engineer" have the same title key; "C++" and
 * "C#" are kept.
 */
public final class DedupKeys {

    static final int MAX_COMPANY_KEY = 200;
    static final int MAX_TITLE_KEY = 300;

    private static final Pattern BRACKETED = Pattern.compile("\\([^()]*\\)|\\[[^\\[\\]]*\\]|\\{[^{}]*\\}");
    private static final Pattern SEGMENT_SEPARATOR = Pattern.compile("\\s+[-\u2013\u2014]\\s+|\\s*\\|\\s*");
    private static final Pattern WORK_MODE = Pattern.compile("(?iu)(?<![\\p{L}\\p{N}])(?:remote|hybrid|on-?site|"
            + "in-?office|anywhere|wfh|work from home|worldwide)(?![\\p{L}\\p{N}])");
    private static final Pattern LETTER_OR_DIGIT = Pattern.compile("[\\p{L}\\p{N}]");
    private static final Pattern PUNCTUATION = Pattern.compile("[^\\p{L}\\p{N}+#\\s]");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private DedupKeys() {
    }

    public static String of(String company, String title, Workplace workplace) {
        return cut(CompanyNames.key(company), MAX_COMPANY_KEY) + "|" + cut(titleKey(title), MAX_TITLE_KEY) + "|"
                + bucket(workplace);
    }

    public static String bucket(Workplace workplace) {
        return workplace == Workplace.REMOTE ? "remote" : "onsite";
    }

    /** The comparison key of a job title; "" for null or blank input. */
    public static String titleKey(String title) {
        if (title == null || title.isBlank()) {
            return "";
        }
        String s = Normalizer.normalize(title, Normalizer.Form.NFKC);
        String previous;
        do {                                             // nested brackets: innermost first
            previous = s;
            s = BRACKETED.matcher(s).replaceAll(" ");
        } while (!s.equals(previous));

        String[] segments = SEGMENT_SEPARATOR.split(s);
        StringBuilder kept = new StringBuilder(segments.length == 0 ? "" : segments[0]);
        for (int i = 1; i < segments.length; i++) {
            if (isPlaceOrWorkMode(segments[i])) {
                break;
            }
            kept.append(' ').append(segments[i]);
        }
        String key = words(kept.toString());
        // A title that is only a place or a bracket ("(Remote)") would otherwise give an empty key.
        return key.isEmpty() ? words(Normalizer.normalize(title, Normalizer.Form.NFKC)) : key;
    }

    /** A title segment naming a place (gazetteer, original case) or a work mode. */
    static boolean isPlaceOrWorkMode(String segment) {
        if (segment == null || !LETTER_OR_DIGIT.matcher(segment).find()) {
            return true;                                 // empty or punctuation only: drop it too
        }
        return WORK_MODE.matcher(segment).find() || Gazetteer.namesPlace(segment);
    }

    private static String words(String text) {
        String s = PUNCTUATION.matcher(text.toLowerCase(Locale.ROOT)).replaceAll(" ").strip();
        if (s.isEmpty()) {
            return "";
        }
        List<String> out = new ArrayList<>();
        for (String token : WHITESPACE.split(s)) {
            if (token.isEmpty()) {
                continue;
            }
            switch (token) {
                case "sr" -> out.add("senior");
                case "jr" -> out.add("junior");
                default -> out.add(token);
            }
        }
        return String.join(" ", out);
    }

    private static String cut(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max).strip();
    }
}
