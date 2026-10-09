package com.talentmatch.feed;

import com.talentmatch.feed.source.LeverAdapter;
import com.talentmatch.feed.source.SourceKind;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * The natural key of a feed source ({@code feed_source.source_key}, UNIQUE in V5). Two requests for
 * the same board produce the same key, so a duplicate add is a 409 and derived Adzuna queries can be
 * reconciled by key.
 * <ul>
 *   <li>Greenhouse and Ashby: {@code greenhouse:acme}, {@code ashby:acme}. Their board tokens are
 *       case-insensitive (probe findings), so the token is lower-cased in the key.</li>
 *   <li>Lever: {@code lever:Acme} (global instance) or {@code lever:eu:Acme} (EU instance). Lever site
 *       names are <b>case-sensitive</b> ({@code Spotify} → 404, {@code spotify} → 200), so the token is
 *       kept exactly as given. Tokens can't contain ':', so the two forms never collide.</li>
 *   <li>Adzuna: {@code adzuna:za:<sha1>}, the SHA-1 of the normalized {@code what} and {@code where}
 *       (trimmed, whitespace collapsed, lower-cased).</li>
 * </ul>
 * Pure; never longer than {@link #MAX_LENGTH}.
 */
public final class SourceKeys {

    /** {@code feed_source.source_key} is varchar(300). */
    public static final int MAX_LENGTH = 300;

    /** Board tokens: the V5 CHECK pattern. */
    public static final Pattern BOARD_TOKEN = Pattern.compile("^[A-Za-z0-9._-]{1,100}$");

    /** Lever instance option values (case-insensitive; absent or blank = global). */
    public static final String LEVER_EU = "eu";
    public static final String LEVER_GLOBAL = "global";

    /** Adzuna options (step 9). */
    public static final String ADZUNA_COUNTRY = "country";
    public static final String ADZUNA_WHAT = "what";
    public static final String ADZUNA_WHERE = "where";

    private static final Pattern ONLY_DOTS = Pattern.compile("^\\.+$");
    private static final Pattern WHITESPACE_RUN = Pattern.compile("\\s+");
    private static final Pattern COUNTRY = Pattern.compile("^[a-z]{2}$");

    private SourceKeys() {
    }

    /**
     * @param options provider options (nullable): {@code leverInstance} for Lever; {@code country},
     *                {@code what}, {@code where} for Adzuna
     * @throws IllegalArgumentException for a missing or malformed token, an unknown Lever instance or an
     *                                  Adzuna query without a two-letter country
     */
    public static String sourceKey(SourceKind kind, String boardToken, Map<String, String> options) {
        Objects.requireNonNull(kind, "kind");
        Map<String, String> opts = options == null ? Map.of() : options;
        return switch (kind) {
            case GREENHOUSE -> "greenhouse:" + requireToken(boardToken).toLowerCase(Locale.ROOT);
            case ASHBY -> "ashby:" + requireToken(boardToken).toLowerCase(Locale.ROOT);
            case LEVER -> isLeverEu(opts.get(LeverAdapter.INSTANCE_OPTION))
                    ? "lever:eu:" + requireToken(boardToken)
                    : "lever:" + requireToken(boardToken);
            case ADZUNA -> adzunaKey(opts);
        };
    }

    /** True for a valid board token (V5 pattern, and not only dots, which would change a URL path). */
    public static boolean isValidToken(String token) {
        return token != null && BOARD_TOKEN.matcher(token).matches() && !ONLY_DOTS.matcher(token).matches();
    }

    /**
     * True when the Lever instance option selects the EU instance.
     *
     * @throws IllegalArgumentException for a value other than "eu", "global" or blank
     */
    public static boolean isLeverEu(String instance) {
        if (instance == null || instance.isBlank()) {
            return false;
        }
        String v = instance.strip().toLowerCase(Locale.ROOT);
        if (v.equals(LEVER_EU)) {
            return true;
        }
        if (v.equals(LEVER_GLOBAL)) {
            return false;
        }
        throw new IllegalArgumentException("options." + LeverAdapter.INSTANCE_OPTION + " must be \"eu\" or \"global\"");
    }

    private static String adzunaKey(Map<String, String> options) {
        String country = options.get(ADZUNA_COUNTRY);
        country = country == null ? "" : country.strip().toLowerCase(Locale.ROOT);
        if (!COUNTRY.matcher(country).matches()) {
            throw new IllegalArgumentException("An Adzuna source needs options.country, a two-letter country code");
        }
        String query = normalizeQuery(options.get(ADZUNA_WHAT)) + "\n" + normalizeQuery(options.get(ADZUNA_WHERE));
        return "adzuna:" + country + ":" + sha1(query);
    }

    private static String normalizeQuery(String value) {
        if (value == null) {
            return "";
        }
        return WHITESPACE_RUN.matcher(value.strip()).replaceAll(" ").toLowerCase(Locale.ROOT);
    }

    private static String requireToken(String token) {
        if (!isValidToken(token)) {
            throw new IllegalArgumentException("Board token must match [A-Za-z0-9._-]{1,100} and not be only dots");
        }
        return token;
    }

    private static String sha1(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-1").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-1 is not available", e);
        }
    }
}
