package com.talentmatch.feed;

import com.talentmatch.feed.source.HtmlText;
import com.talentmatch.feed.source.RawPosting;
import com.talentmatch.preferences.Gazetteer;
import com.talentmatch.preferences.SalaryPeriod;
import com.talentmatch.preferences.Seniority;
import com.talentmatch.preferences.Workplace;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * {@link RawPosting} → {@link NormalizedPosting} (§4.4, pure): the values the V5 columns accept.
 * <ul>
 *   <li>External id: stripped, 1..200 characters.</li>
 *   <li>URL: http(s) only, at most 2000 characters.</li>
 *   <li>Title and company: control characters removed, whitespace collapsed, cut to 300 / 200.</li>
 *   <li>Description: HTML → text (jsoup); plain text tidied (line structure kept, at most one blank
 *       line in a row); cut to 20000 characters at a word boundary; blank → null.</li>
 *   <li>Location cut to 500, employment type to 50; country must be an ISO alpha-2 code.</li>
 *   <li>Salary: negative amounts dropped, min/max swapped when reversed, 2 decimals, amounts beyond
 *       {@code numeric(14,2)} dropped; currency only with an amount and only as three letters.</li>
 *   <li>Seniority from the title; a content hash over every stored content field.</li>
 * </ul>
 * A posting that can't be stored (no id, no title, no company, no http(s) URL) throws
 * {@link InvalidPostingException}; the poller counts it as skipped. Messages never hold posting text.
 */
public final class PostingNormalizer {

    public static final int MAX_EXTERNAL_ID = 200;
    public static final int MAX_URL = 2000;
    public static final int MAX_TITLE = 300;
    public static final int MAX_COMPANY = 200;
    public static final int MAX_DESCRIPTION = 20_000;
    public static final int MAX_LOCATION = 500;
    public static final int MAX_EMPLOYMENT_TYPE = 50;

    /** numeric(14,2): at most 12 digits before the point. */
    private static final BigDecimal MAX_AMOUNT = new BigDecimal("999999999999.99");
    /** Case-sensitive like the V5 CHECK ({@code ~ '^https?://'}); the scheme is lower-cased first. */
    private static final Pattern HTTP_URL = Pattern.compile("^https?://\\S+$");
    private static final Pattern CURRENCY = Pattern.compile("^[A-Z]{3}$");
    private static final Pattern CONTROL = Pattern.compile("[\\p{Cntrl}\\p{Cf}&&[^\\n\\t]]");
    private static final Pattern WHITESPACE_RUN = Pattern.compile("\\s+");
    private static final Pattern SPACES = Pattern.compile("[ \\t\\x0B\\f\\u00A0\\u2007\\u202F]+");
    private static final Pattern BLANK_LINES = Pattern.compile("\\n{3,}");
    private static final char FIELD_SEPARATOR = '\u001F';

    private PostingNormalizer() {
    }

    /** A posting that can't be stored. The message names the problem, never the posting's text. */
    public static final class InvalidPostingException extends IllegalArgumentException {

        private static final long serialVersionUID = 1L;

        InvalidPostingException(String message) {
            super(message);
        }
    }

    public static NormalizedPosting normalize(RawPosting raw) {
        if (raw == null) {
            throw new InvalidPostingException("no posting");
        }
        String externalId = raw.externalId() == null ? null : raw.externalId().strip();
        if (externalId == null || externalId.isEmpty() || externalId.length() > MAX_EXTERNAL_ID) {
            throw new InvalidPostingException("missing or oversized external id");
        }
        String url = lowerScheme(raw.url() == null ? null : raw.url().strip());
        if (url == null || url.length() > MAX_URL || !HTTP_URL.matcher(url).matches()) {
            throw new InvalidPostingException("missing, oversized or non-http(s) URL");
        }
        String title = cut(oneLine(raw.title()), MAX_TITLE);
        if (title == null) {
            throw new InvalidPostingException("missing title");
        }
        String company = cut(oneLine(raw.company()), MAX_COMPANY);
        if (company == null) {
            throw new InvalidPostingException("missing company");
        }

        String description = description(raw.descriptionHtmlOrText(), raw.descriptionIsHtml());
        boolean descriptionComplete = raw.descriptionComplete() && description != null;
        String location = cut(oneLine(raw.locationText()), MAX_LOCATION);
        String country = country(raw.countryCode());
        Workplace workplace = raw.workplace() == null ? Workplace.UNKNOWN : raw.workplace();
        String employmentType = cut(oneLine(raw.employmentType()), MAX_EMPLOYMENT_TYPE);

        BigDecimal min = amount(raw.salaryMin());
        BigDecimal max = amount(raw.salaryMax());
        if (min != null && max != null && min.compareTo(max) > 0) {
            BigDecimal swap = min;
            min = max;
            max = swap;
        }
        boolean hasSalary = min != null || max != null;
        String currency = hasSalary ? currency(raw.salaryCurrency()) : null;
        SalaryPeriod period = hasSalary ? raw.salaryPeriod() : null;
        boolean estimated = hasSalary && raw.salaryEstimated();

        Seniority seniority = Seniority.fromTitle(title);
        String hash = contentHash(url, title, company, description, location, country, workplace, employmentType,
                min, max, currency, period, estimated, raw.postedAt());
        return new NormalizedPosting(externalId, url, title, company, description, descriptionComplete, location,
                country, workplace, employmentType, min, max, currency, period, estimated, raw.postedAt(),
                raw.sourceUpdatedAt(), raw.contentVersion(), seniority, hash);
    }

    /** Plain text of the description, cleaned and capped; null when blank. */
    static String description(String value, boolean isHtml) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String text = isHtml ? HtmlText.toText(value) : tidy(value);
        if (text == null || text.isBlank()) {
            return null;
        }
        return cutAtWord(text, MAX_DESCRIPTION);
    }

    /** sha256 (lower-case hex) of UTF-8 text. */
    public static String sha256Hex(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    /** Control characters removed, whitespace (line breaks included) collapsed; null when blank. */
    static String oneLine(String value) {
        if (value == null) {
            return null;
        }
        String s = CONTROL.matcher(value).replaceAll("");
        s = WHITESPACE_RUN.matcher(s).replaceAll(" ").strip();
        return s.isEmpty() ? null : s;
    }

    /** Plain text with its lines kept: CRLF → LF, control characters removed, spaces collapsed per line. */
    private static String tidy(String value) {
        String s = value.replace("\r\n", "\n").replace('\r', '\n');
        s = CONTROL.matcher(s).replaceAll("");
        StringBuilder out = new StringBuilder(s.length());
        for (String line : s.split("\n", -1)) {
            out.append(SPACES.matcher(line).replaceAll(" ").strip()).append('\n');
        }
        return BLANK_LINES.matcher(out.toString()).replaceAll("\n\n").strip();
    }

    private static String cut(String value, int max) {
        if (value == null) {
            return null;
        }
        if (value.length() <= max) {
            return value;
        }
        int end = max;
        if (Character.isHighSurrogate(value.charAt(end - 1))) {
            end--;                                           // never split a surrogate pair
        }
        String s = value.substring(0, end).strip();
        return s.isEmpty() ? null : s;
    }

    /** Cut to {@code max} characters, preferring the last whitespace in the final 10%. */
    static String cutAtWord(String value, int max) {
        if (value.length() <= max) {
            return value;
        }
        int end = max;
        for (int i = max; i > max * 9 / 10; i--) {
            if (Character.isWhitespace(value.charAt(i))) {
                end = i;
                break;
            }
        }
        if (Character.isHighSurrogate(value.charAt(end - 1))) {
            end--;
        }
        return value.substring(0, end).strip();
    }

    /** "HTTPS://x" → "https://x"; anything without a scheme is returned unchanged (and then rejected). */
    private static String lowerScheme(String url) {
        if (url == null) {
            return null;
        }
        int colon = url.indexOf("://");
        if (colon <= 0 || colon > 5) {
            return url;
        }
        return url.substring(0, colon).toLowerCase(Locale.ROOT) + url.substring(colon);
    }

    private static String country(String value) {
        if (value == null) {
            return null;
        }
        String code = value.strip().toUpperCase(Locale.ROOT);
        return Gazetteer.isCountryCode(code) ? code : null;
    }

    private static String currency(String value) {
        if (value == null) {
            return null;
        }
        String code = value.strip().toUpperCase(Locale.ROOT);
        return CURRENCY.matcher(code).matches() ? code : null;
    }

    private static BigDecimal amount(BigDecimal value) {
        if (value == null || value.signum() < 0) {
            return null;
        }
        BigDecimal scaled = value.setScale(2, RoundingMode.HALF_UP);
        return scaled.compareTo(MAX_AMOUNT) > 0 ? null : scaled;
    }

    private static String contentHash(String url, String title, String company, String description, String location,
                                      String country, Workplace workplace, String employmentType, BigDecimal min,
                                      BigDecimal max, String currency, SalaryPeriod period, boolean estimated,
                                      Instant postedAt) {
        StringBuilder s = new StringBuilder(256 + (description == null ? 0 : description.length()));
        append(s, url);
        append(s, title);
        append(s, company);
        append(s, description);
        append(s, location);
        append(s, country);
        append(s, workplace.name());
        append(s, employmentType);
        append(s, min == null ? null : min.toPlainString());
        append(s, max == null ? null : max.toPlainString());
        append(s, currency);
        append(s, period == null ? null : period.name());
        append(s, String.valueOf(estimated));
        append(s, postedAt == null ? null : postedAt.toString());
        return sha256Hex(s.toString());
    }

    private static void append(StringBuilder s, String value) {
        s.append(value == null ? "\u0000" : value).append(FIELD_SEPARATOR);
    }
}
