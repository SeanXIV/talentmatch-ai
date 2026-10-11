package com.talentmatch.feed.source;

import com.talentmatch.preferences.Gazetteer;
import java.text.Normalizer;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Free-text country (Ashby {@code addressCountry}, the last part of a Greenhouse location) to ISO
 * 3166 alpha-2, pure. Exact match after normalization (case, accents ignored, trailing dot removed)
 * against: the ISO code itself, the English name, the {@link Gazetteer} aliases and a few more
 * ("USA", "US", "UK", "South Korea", …). Regions ("European Union", "EMEA") and unknown text → null.
 */
public final class CountryNames {

    private static final Map<String, String> EXTRA_ALIASES = Map.ofEntries(
            Map.entry("united states of america", "US"),
            Map.entry("usa", "US"),
            Map.entry("u.s.a", "US"),
            Map.entry("u.s", "US"),
            Map.entry("united kingdom of great britain and northern ireland", "GB"),
            Map.entry("u.k", "GB"),
            Map.entry("south korea", "KR"),
            Map.entry("republic of korea", "KR"),
            Map.entry("korea, republic of", "KR"),
            Map.entry("czechia", "CZ"),
            Map.entry("russia", "RU"),
            Map.entry("vietnam", "VN"),
            Map.entry("viet nam", "VN"),
            Map.entry("taiwan", "TW"),
            Map.entry("the netherlands", "NL"),
            Map.entry("turkiye", "TR"),
            Map.entry("ivory coast", "CI"),
            Map.entry("cote d'ivoire", "CI"),
            Map.entry("bosnia", "BA"),
            Map.entry("macedonia", "MK"),
            Map.entry("eswatini", "SZ"),
            Map.entry("swaziland", "SZ"),
            Map.entry("cape verde", "CV"),
            Map.entry("uae", "AE"),
            Map.entry("rsa", "ZA"));

    private static final Pattern MARKS = Pattern.compile("\\p{M}+");
    private static final Pattern SPACES = Pattern.compile("\\s+");

    private static final Map<String, String> BY_NAME = buildIndex();

    private CountryNames() {
    }

    /** ISO alpha-2 for a country name or code, or null. */
    public static String toIso2(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String stripped = text.strip();
        if (stripped.length() == 2) {
            String code = stripped.toUpperCase(Locale.ROOT);
            if (Gazetteer.isCountryCode(code)) {
                return code;
            }
        }
        return BY_NAME.get(key(stripped));
    }

    private static Map<String, String> buildIndex() {
        Map<String, String> index = new HashMap<>();
        for (String code : Locale.getISOCountries()) {
            for (String name : Gazetteer.countryNames(code)) {
                index.putIfAbsent(key(name), code);
            }
        }
        EXTRA_ALIASES.forEach((name, code) -> index.put(key(name), code));
        return Map.copyOf(index);
    }

    private static String key(String name) {
        String folded = MARKS.matcher(Normalizer.normalize(name, Normalizer.Form.NFKD)).replaceAll("");
        folded = SPACES.matcher(folded.toLowerCase(Locale.ROOT).replace('’', '\'')).replaceAll(" ").strip();
        while (folded.endsWith(".")) {
            folded = folded.substring(0, folded.length() - 1);
        }
        return folded;
    }
}
