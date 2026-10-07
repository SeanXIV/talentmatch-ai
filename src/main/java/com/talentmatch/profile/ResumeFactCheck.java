package com.talentmatch.profile;

import com.talentmatch.profile.ProfileDocument.Certification;
import com.talentmatch.profile.ProfileDocument.Education;
import com.talentmatch.profile.ProfileDocument.Experience;
import com.talentmatch.profile.ProfileDocument.Link;
import com.talentmatch.profile.ProfileDocument.Project;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Never-invent checks on the details of an AI draft, beyond names ({@link ResumeGrounding}):
 * numbers in free text ("cut latency 40%"), the years of dates, contact details, URLs, and
 * whether each highlight is really the CV's own wording. Every finding is a warning; no value is
 * changed or dropped. Pure: no I/O.
 */
final class ResumeFactCheck {

    /** A highlight must share at least this share of its content words with the CV. */
    static final double HIGHLIGHT_MIN_OVERLAP = 0.70;
    /** Content words: at least this many letters (skips "and", "the", "with"). */
    static final int CONTENT_WORD_MIN_LETTERS = 4;

    private static final Pattern FORMAT = Pattern.compile("\\p{Cf}");
    /** 40, 40%, 1.5, 10,000, 2021 (a number may contain . or , between digits). */
    private static final Pattern NUMBER = Pattern.compile("\\d+(?:[.,]\\d+)*");
    private static final Pattern CONTENT_WORD = Pattern.compile("\\p{L}{" + CONTENT_WORD_MIN_LETTERS + ",}");
    private static final Pattern NON_DIGIT = Pattern.compile("\\D");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final Pattern URL_PREFIX = Pattern.compile("^(?:[a-z][a-z0-9+.-]*://)?(?:www\\.)?");

    private final String lower;           // NFKC, format chars removed, lower case
    private final String compact;         // lower without whitespace (URLs split across lines)
    private final String digits;          // every digit of the CV, in order
    private final Set<String> numbers = new HashSet<>();
    private final Set<String> words = new HashSet<>();
    private final List<ProfileWarning> warnings = new ArrayList<>();

    private ResumeFactCheck(String cvText) {
        String t = FORMAT.matcher(Normalizer.normalize(cvText == null ? "" : cvText, Normalizer.Form.NFKC))
                .replaceAll("");
        lower = t.toLowerCase(Locale.ROOT);
        compact = WHITESPACE.matcher(lower).replaceAll("");
        digits = NON_DIGIT.matcher(lower).replaceAll("");
        Matcher n = NUMBER.matcher(lower);
        while (n.find()) {
            numbers.add(canonicalNumber(n.group()));
            // "2019-2021" or "03/2021" also state the single numbers
            for (String part : n.group().split("[.,]")) {
                numbers.add(part);
            }
        }
        Matcher w = CONTENT_WORD.matcher(lower);
        while (w.find()) {
            words.add(w.group());
        }
    }

    static List<ProfileWarning> check(ProfileDocument doc, String cvText) {
        ResumeFactCheck c = new ResumeFactCheck(cvText);
        c.document(doc);
        return List.copyOf(c.warnings);
    }

    private void document(ProfileDocument d) {
        email("email", d.email());
        phone("phone", d.phone());
        numbersIn("summary", d.summary());
        for (int i = 0; i < d.links().size(); i++) {
            Link l = d.links().get(i);
            url("links[" + i + "].url", l.url());
        }
        for (int i = 0; i < d.experience().size(); i++) {
            Experience e = d.experience().get(i);
            String p = "experience[" + i + "]";
            dateYear(p + ".startDate", e.startDate());
            dateYear(p + ".endDate", e.endDate());
            highlights(p + ".highlights", e.highlights());
        }
        for (int i = 0; i < d.projects().size(); i++) {
            Project pr = d.projects().get(i);
            String p = "projects[" + i + "]";
            numbersIn(p + ".description", pr.description());
            url(p + ".url", pr.url());
            highlights(p + ".highlights", pr.highlights());
        }
        for (int i = 0; i < d.certifications().size(); i++) {
            Certification c = d.certifications().get(i);
            String p = "certifications[" + i + "]";
            dateYear(p + ".issued", c.issued());
            dateYear(p + ".expires", c.expires());
            url(p + ".url", c.url());
        }
        for (int i = 0; i < d.education().size(); i++) {
            Education e = d.education().get(i);
            String p = "education[" + i + "]";
            dateYear(p + ".startDate", e.startDate());
            dateYear(p + ".endDate", e.endDate());
        }
    }

    private void highlights(String field, List<String> bullets) {
        for (int i = 0; i < bullets.size(); i++) {
            String path = field + "[" + i + "]";
            String bullet = bullets.get(i);
            numbersIn(path, bullet);
            wording(path, bullet);
        }
    }

    /** Every number in the text must be in the CV ("40%" needs a 40 in the CV). */
    private void numbersIn(String path, String text) {
        if (text == null) {
            return;
        }
        Set<String> missing = new LinkedHashSet<>();
        Matcher m = NUMBER.matcher(text);
        while (m.find()) {
            if (!numbers.contains(canonicalNumber(m.group()))) {
                missing.add(m.group());
            }
        }
        if (!missing.isEmpty()) {
            warn(path, String.join(", ", missing), (missing.size() == 1 ? "The number " : "The numbers ")
                    + String.join(", ", missing) + (missing.size() == 1 ? " is" : " are")
                    + " not in your CV text. Check it before saving (the AI may have invented or changed it).");
        }
    }

    /** At least 70% of the bullet's content words must appear in the CV. */
    private void wording(String path, String bullet) {
        Matcher m = CONTENT_WORD.matcher(Normalizer.normalize(bullet, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT));
        int total = 0;
        int found = 0;
        while (m.find()) {
            total++;
            if (words.contains(m.group())) {
                found++;
            }
        }
        if (total > 0 && found < HIGHLIGHT_MIN_OVERLAP * total) {
            warn(path, ProfileNormalizer.cut(bullet, 60), "This point is reworded or not in your CV. Check it says "
                    + "what your CV says before saving.");
        }
    }

    /** The year of a YYYY or YYYY-MM date must be written in the CV. */
    private void dateYear(String path, String date) {
        if (date == null || date.length() < 4) {
            return;
        }
        String year = date.substring(0, 4);
        if (!numbers.contains(year)) {
            warn(path, date, "The year " + year + " is not in your CV text. Check this date before saving.");
        }
    }

    private void email(String path, String email) {
        if (email != null && !lower.contains(email.toLowerCase(Locale.ROOT))) {
            warn(path, email, "This email address is not in your CV text. Check it before saving.");
        }
    }

    /** Phone numbers are compared by their digits only (spaces, dashes and brackets vary). */
    private void phone(String path, String phone) {
        if (phone == null) {
            return;
        }
        String d = NON_DIGIT.matcher(phone).replaceAll("");
        if (!d.isEmpty() && !digits.contains(d)) {
            warn(path, phone, "This phone number is not in your CV text. Check it before saving.");
        }
    }

    /** URLs are compared without scheme, "www." and a trailing slash, ignoring case and line breaks. */
    private void url(String path, String url) {
        if (url == null) {
            return;
        }
        String u = URL_PREFIX.matcher(WHITESPACE.matcher(url.toLowerCase(Locale.ROOT)).replaceAll("")).replaceAll("");
        while (u.endsWith("/")) {
            u = u.substring(0, u.length() - 1);
        }
        if (!u.isEmpty() && !compact.contains(u)) {
            warn(path, url, "This link is not in your CV text. Check it before saving.");
        }
    }

    /** "10,000" = "10000"; "1.5" stays; a trailing ".0" is not removed (rare in CVs). */
    private static String canonicalNumber(String n) {
        return n.replaceAll(",(?=\\d{3}(?:\\D|$))", "");
    }

    private void warn(String path, String value, String message) {
        warnings.add(new ProfileWarning(path, value, message));
    }
}
