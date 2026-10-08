package com.talentmatch.preferences;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Place names found in free-text locations (pure, static data): every ISO 3166 country by its
 * English name, common aliases ("USA", "UK", "RSA") and world regions ("EMEA", "Europe", "LATAM").
 * Matching is phrase-based with letter/digit boundaries, case-insensitive, except short upper-case
 * codes ("US", "UK", "EU"), which match only in upper case so "join us" is not the United States.
 * Deliberately small: no cities. A location the gazetteer doesn't know counts as "no place named".
 */
public final class Gazetteer {

    private static final Set<String> ISO_COUNTRIES = Set.of(Locale.getISOCountries());

    /** Extra names per ISO code (the English display name is always included). */
    private static final Map<String, List<String>> ALIASES = Map.ofEntries(
            Map.entry("ZA", List.of("RSA", "Mzansi")),
            Map.entry("US", List.of("United States of America", "USA", "U.S.", "U.S.A.", "US", "America")),
            Map.entry("GB", List.of("UK", "U.K.", "Great Britain", "Britain", "England", "Scotland", "Wales",
                    "Northern Ireland")),
            Map.entry("AE", List.of("UAE", "Dubai", "Abu Dhabi")),
            Map.entry("NL", List.of("Holland")),
            Map.entry("KR", List.of("Korea")),
            Map.entry("CZ", List.of("Czech Republic")),
            Map.entry("TR", List.of("Turkey", "Türkiye")),
            Map.entry("CI", List.of("Ivory Coast")),
            Map.entry("HK", List.of("Hong Kong")),
            Map.entry("MO", List.of("Macau", "Macao")));

    /** Regions that are not countries. */
    private static final List<String> REGIONS = List.of("Europe", "European Union", "EU", "EEA", "EMEA", "APAC",
            "Asia", "Asia Pacific", "LATAM", "Latin America", "North America", "South America", "Central America",
            "Americas", "Africa", "Sub-Saharan Africa", "Middle East", "MENA", "Oceania", "Nordics", "Scandinavia",
            "DACH", "Benelux", "Caribbean");

    /** Upper-case codes that are also ordinary words or too short to match case-insensitively. */
    private static final Set<String> CASE_SENSITIVE = Set.of("US", "UK", "EU", "EEA", "USA", "UAE", "RSA", "DACH",
            "MENA", "U.S.", "U.K.");

    private static final Pattern ANY_PLACE = compile(allPlaceNames());
    private static final Map<String, Pattern> BY_COUNTRY = new ConcurrentHashMap<>();
    private static final Map<String, Pattern> BY_PHRASE = new ConcurrentHashMap<>();

    private Gazetteer() {
    }

    public static boolean isCountryCode(String code) {
        return code != null && ISO_COUNTRIES.contains(code);
    }

    /** True if the text names any known country or region. */
    public static boolean namesPlace(String text) {
        return text != null && ANY_PLACE.matcher(normalize(text)).find();
    }

    /** True if the text names this country (ISO alpha-2), by its English name, an alias or its upper-case code. */
    public static boolean namesCountry(String text, String iso2) {
        if (text == null || !isCountryCode(iso2)) {
            return false;
        }
        Pattern p = BY_COUNTRY.computeIfAbsent(iso2, code -> {
            List<String> names = new ArrayList<>(countryNames(code));
            names.add(code);                         // "ZA" in upper case only (CASE_SENSITIVE handling below)
            return compile(names);
        });
        return p.matcher(normalize(text)).find();
    }

    /** True if the text contains the phrase as whole words, ignoring case. */
    public static boolean containsPhrase(String text, String phrase) {
        if (text == null || phrase == null || phrase.isBlank()) {
            return false;
        }
        Pattern p = BY_PHRASE.computeIfAbsent(phrase.strip().toLowerCase(Locale.ROOT),
                k -> Pattern.compile(boundaryed(Pattern.quote(k)),
                        Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE | Pattern.UNICODE_CHARACTER_CLASS));
        return p.matcher(normalize(text)).find();
    }

    /** English name plus aliases of a country. */
    public static List<String> countryNames(String iso2) {
        List<String> names = new ArrayList<>();
        @SuppressWarnings("deprecation")
        String english = new Locale("", iso2).getDisplayCountry(Locale.ENGLISH);
        if (english != null && !english.isBlank() && !english.equals(iso2)) {
            names.add(english);
        }
        names.addAll(ALIASES.getOrDefault(iso2, List.of()));
        return names;
    }

    private static List<String> allPlaceNames() {
        Set<String> names = new LinkedHashSet<>(REGIONS);
        for (String code : ISO_COUNTRIES) {
            names.addAll(countryNames(code));
        }
        return new ArrayList<>(names);
    }

    /**
     * One alternation, longest names first. Names in {@link #CASE_SENSITIVE} and two-letter ISO
     * codes are matched case-sensitively, everything else case-insensitively.
     */
    private static Pattern compile(List<String> names) {
        String alternation = names.stream()
                .distinct()
                .sorted(Comparator.comparingInt(String::length).reversed().thenComparing(Comparator.naturalOrder()))
                .map(n -> caseSensitive(n) ? Pattern.quote(n) : "(?iu:" + Pattern.quote(n) + ")")
                .collect(Collectors.joining("|"));
        return Pattern.compile(boundaryed("(?:" + alternation + ")"), Pattern.UNICODE_CHARACTER_CLASS);
    }

    private static boolean caseSensitive(String name) {
        return CASE_SENSITIVE.contains(name) || (name.length() == 2 && name.equals(name.toUpperCase(Locale.ROOT)));
    }

    private static String boundaryed(String regex) {
        return "(?<![\\p{L}\\p{N}])" + regex + "(?![\\p{L}\\p{N}])";
    }

    private static String normalize(String text) {
        return Normalizer.normalize(text, Normalizer.Form.NFKC);
    }

    /** For diagnostics/tests: every name the gazetteer knows. */
    static List<String> names() {
        return List.copyOf(allPlaceNames());
    }
}
