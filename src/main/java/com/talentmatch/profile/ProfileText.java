package com.talentmatch.profile;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Text helpers shared by the never-invent checks: Phase 4's CV grounding ({@link ResumeGrounding},
 * {@link ResumeFactCheck}) and Phase 7's tailored documents. Pure and thread-safe.
 */
public final class ProfileText {

    /** Content words: at least this many letters (skips "and", "the", "with"). */
    public static final int CONTENT_WORD_MIN_LETTERS = 4;

    /** 40, 40%, 1.5, 10,000, 2021 (a number may contain . or , between digits). */
    public static final Pattern NUMBER = Pattern.compile("\\d+(?:[.,]\\d+)*");

    private static final Pattern NON_WORD = Pattern.compile("[^\\p{L}\\p{N}+#]+");
    private static final Pattern FORMAT = Pattern.compile("\\p{Cf}");
    /** One content word in text that is already NFKC and lower case (see {@link #contentWords}). */
    public static final Pattern CONTENT_WORD = Pattern.compile("\\p{L}{" + CONTENT_WORD_MIN_LETTERS + ",}");
    private static final Pattern THOUSANDS_SEPARATOR = Pattern.compile(",(?=\\d{3}(?:\\D|$))");
    private static final Pattern WORD = Pattern.compile("\\p{L}+");

    private static final Map<Integer, String> NUMBER_WORDS = Map.ofEntries(Map.entry(1, "one"), Map.entry(2, "two"),
            Map.entry(3, "three"), Map.entry(4, "four"), Map.entry(5, "five"), Map.entry(6, "six"),
            Map.entry(7, "seven"), Map.entry(8, "eight"), Map.entry(9, "nine"), Map.entry(10, "ten"),
            Map.entry(11, "eleven"), Map.entry(12, "twelve"), Map.entry(13, "thirteen"), Map.entry(14, "fourteen"),
            Map.entry(15, "fifteen"), Map.entry(16, "sixteen"), Map.entry(17, "seventeen"),
            Map.entry(18, "eighteen"), Map.entry(19, "nineteen"), Map.entry(20, "twenty"));
    private static final Map<String, Integer> NUMBER_VALUES = invert(NUMBER_WORDS);

    private ProfileText() {
    }

    /**
     * NFKC (ligatures, full-width forms), format characters removed (soft hyphens, zero-width
     * spaces), lower case, punctuation to spaces, single spaces. Keeps + and # (C++, C#).
     */
    public static String key(String s) {
        if (s == null) {
            return "";
        }
        String t = FORMAT.matcher(Normalizer.normalize(s, Normalizer.Form.NFKC)).replaceAll("");
        return NON_WORD.matcher(t.toLowerCase(Locale.ROOT)).replaceAll(" ").strip();
    }

    /** "10,000" = "10000"; "1.5" stays; a trailing ".0" is not removed (rare in CVs). */
    public static String canonicalNumber(String n) {
        return THOUSANDS_SEPARATOR.matcher(n).replaceAll("");
    }

    /**
     * Every number written in {@code text} ({@link #NUMBER}), canonical ({@link #canonicalNumber}),
     * plus each of its {@code .}/{@code ,}-separated parts: "2019-2021" or "03/2021" also state the
     * single numbers. Number words are not included (see {@link #numberWordValues}).
     */
    public static Set<String> numbers(String text) {
        Set<String> out = new HashSet<>();
        if (text == null) {
            return out;
        }
        Matcher n = NUMBER.matcher(text);
        while (n.find()) {
            out.add(canonicalNumber(n.group()));
            for (String part : n.group().split("[.,]")) {
                out.add(part);
            }
        }
        return out;
    }

    /**
     * Every number written in {@code text} ({@link #NUMBER}), canonical ({@link #canonicalNumber}), whole
     * only: "12,000" states 12000, not 12 or 000; "1.5" states 1.5, not 1 or 5. Phase 7's grounding uses
     * this, because a checked text's numbers are compared as whole tokens too (decision h: exact after
     * canonicalising). Phase 4's {@link #numbers} keeps the parts. Number words are not included.
     */
    public static Set<String> wholeNumbers(String text) {
        Set<String> out = new HashSet<>();
        if (text == null) {
            return out;
        }
        Matcher n = NUMBER.matcher(text);
        while (n.find()) {
            out.add(canonicalNumber(n.group()));
        }
        return out;
    }

    /** The word for 1..20 ("five"), or null for any other number. */
    public static String numberWord(int n) {
        return NUMBER_WORDS.get(n);
    }

    /** The value of a number word "one".."twenty" (any case), or null. */
    public static Integer numberWordValue(String word) {
        return word == null ? null : NUMBER_VALUES.get(word.toLowerCase(Locale.ROOT));
    }

    /** The digits ("5") of every whole number word one..twenty in {@code text}. */
    public static Set<String> numberWordValues(String text) {
        Set<String> out = new HashSet<>();
        if (text == null) {
            return out;
        }
        Matcher w = WORD.matcher(text);
        while (w.find()) {
            Integer v = numberWordValue(w.group());
            if (v != null) {
                out.add(Integer.toString(v));
            }
        }
        return out;
    }

    /**
     * The content words ({@value #CONTENT_WORD_MIN_LETTERS}+ letters) of {@code text}, NFKC and lower
     * case, in order and with repeats.
     */
    public static List<String> contentWords(String text) {
        List<String> out = new ArrayList<>();
        if (text == null) {
            return out;
        }
        Matcher m = CONTENT_WORD.matcher(Normalizer.normalize(text, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT));
        while (m.find()) {
            out.add(m.group());
        }
        return out;
    }

    private static Map<String, Integer> invert(Map<Integer, String> words) {
        Map<String, Integer> out = new HashMap<>();
        words.forEach((k, v) -> out.put(v, k));
        return Map.copyOf(out);
    }
}
