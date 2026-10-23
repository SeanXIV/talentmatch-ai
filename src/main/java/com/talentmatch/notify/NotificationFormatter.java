package com.talentmatch.notify;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Renders notifications as subject + plain text (§4.9). Pure. No CV data: no name, contact details,
 * profile text, experience or years; only the job's own facts and its skill names.
 *
 * <p>Third-party text has control characters removed (CR and LF included, which blocks header
 * injection in the subject), whitespace collapsed and per-field caps applied (title 120, company 80,
 * location 120). URLs are printed only when they start with {@code http://} or {@code https://}.
 * The subject is at most 150 characters (the title is cut first), the body at most 4000.
 */
public final class NotificationFormatter {

    public static final int MAX_SUBJECT = 150;
    public static final int MAX_BODY = 4000;
    static final int MAX_TITLE = 120;
    static final int MAX_COMPANY = 80;
    static final int MAX_LOCATION = 120;
    static final int MAX_SKILL = 60;
    static final int MAX_SUMMARY = 300;
    static final int MAX_URL = 2000;
    static final int MAX_MATCHED = 8;
    static final int MAX_MISSING = 5;
    static final int MIN_SUBJECT_TITLE = 20;
    static final String ELLIPSIS = "…";
    static final String TEST_SUBJECT = "[TalentMatch] Test notification";

    private static final Pattern HTTP_URL = Pattern.compile("^https?://[^\\s]+$", Pattern.CASE_INSENSITIVE);
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm z", Locale.ENGLISH);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm z", Locale.ENGLISH);

    private NotificationFormatter() {
    }

    /** The message key of a feed notification: stable per notification id (the Message-ID's left part). */
    public static String feedKey(UUID notificationId) {
        return "feed-" + notificationId;
    }

    public static String testKey(UUID testId) {
        return "test-" + testId;
    }

    /**
     * The email for one feed notification.
     *
     * @param score    the score the row was queued with (agrees with the history)
     * @param minScore the current threshold (for the footer)
     */
    public static NotificationMessage feed(UUID notificationId, NotificationContent c, double score, double minScore,
                                           ZoneId zone, String appBaseUrl, Instant now) {
        String title = orDefault(clean(c.title(), MAX_TITLE), "Untitled job");
        String company = orDefault(clean(c.company(), MAX_COMPANY), "Unknown company");
        int pct = percent(score);

        StringBuilder b = new StringBuilder(1024);
        b.append(title).append('\n');
        b.append(company).append(" · ").append(workplaceWord(c.workplace()));
        String where = location(c);
        if (!where.isEmpty()) {
            b.append(", ").append(where);
        }
        b.append('\n');
        b.append("Score: ").append(pct).append('%');
        String summary = clean(c.summary(), MAX_SUMMARY);
        if (!summary.isEmpty()) {
            b.append(" (").append(summary).append(')');
        }
        b.append('\n');
        String firstSeen = c.firstSeenAt() == null ? "unknown" : TIME.format(c.firstSeenAt().atZone(zone));
        if (c.postedAt() == null) {
            b.append("Posted: not stated by the source; first seen ").append(firstSeen).append('\n');
        } else {
            b.append("Posted: ").append(relative(c.postedAt(), now)).append(" (")
                    .append(DATE_TIME.format(c.postedAt().atZone(zone))).append("); first seen ")
                    .append(firstSeen).append('\n');
        }
        b.append("Matched: ").append(skills(c.matchedRequired(), MAX_MATCHED)).append('\n');
        b.append("Missing: ").append(skills(c.missingRequired(), MAX_MISSING)).append('\n');
        List<String> notes = flagWords(c.flags());
        if (!notes.isEmpty()) {
            b.append("Notes: ").append(String.join(", ", notes)).append('\n');
        }
        b.append('\n');
        String apply = url(c.primaryUrl());
        if (apply != null) {
            b.append("Apply: ").append(apply).append('\n');
        }
        b.append("Details: ").append(appBaseUrl == null ? "" : appBaseUrl).append("/api/feed/jobs/")
                .append(c.jobId()).append('\n');
        b.append("Source: ").append(sources(c.sourceKinds()));
        if (c.adzunaAttached()) {
            b.append("; Jobs by Adzuna");
        }
        b.append('\n');
        b.append('\n');
        b.append("--\n");
        b.append("TalentMatch sent this because the job scored at least ").append(percent(minScore))
                .append("%. Change it with PUT /api/notifications/settings.\n");

        return new NotificationMessage(subject(title, company, pct), capBody(b.toString()), feedKey(notificationId));
    }

    /** The fixed test email: the settings summary, no job or CV data. */
    public static NotificationMessage test(UUID testId, boolean enabled, double minScore, int maxPerHour, ZoneId zone,
                                           Instant now) {
        String body = "This is a test notification from TalentMatch. If you can read it, email delivery works.\n"
                + "\n"
                + "Notifications: " + (enabled ? "enabled" : "disabled (turn them on with PUT /api/notifications/settings)")
                + "\n"
                + "Minimum score: " + percent(minScore) + "%\n"
                + "Hourly cap: " + maxPerHour + " emails\n"
                + "Sent: " + DATE_TIME.format(now.atZone(zone)) + "\n"
                + "\n"
                + "--\n"
                + "Change the settings with PUT /api/notifications/settings.\n";
        return new NotificationMessage(TEST_SUBJECT, capBody(body), testKey(testId));
    }

    // ------------------------------------------------------------------ helpers

    /** {@code [TalentMatch] 80% match: {title} at {company}}, at most 150 chars; the title is cut first. */
    static String subject(String title, String company, int pct) {
        String prefix = "[TalentMatch] " + pct + "% match: ";
        String t = clean(title, MAX_TITLE);
        String c = clean(company, MAX_COMPANY);
        String full = prefix + t + " at " + c;
        if (full.length() <= MAX_SUBJECT) {
            return full;
        }
        int titleRoom = MAX_SUBJECT - prefix.length() - " at ".length() - c.length();
        if (titleRoom >= MIN_SUBJECT_TITLE) {
            return prefix + cap(t, titleRoom) + " at " + c;
        }
        t = cap(t, MIN_SUBJECT_TITLE);
        int companyRoom = MAX_SUBJECT - prefix.length() - " at ".length() - t.length();
        return prefix + t + " at " + cap(c, Math.max(1, companyRoom));
    }

    /**
     * Removes control and format characters (CR, LF, tabs, zero-width and bidi controls, line and
     * paragraph separators), collapses whitespace, strips, and caps at {@code max} with {@code …}.
     * Null → "".
     */
    static String clean(String value, int max) {
        if (value == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(Math.min(value.length(), max + 16));
        value.codePoints().forEach(cp -> {
            int type = Character.getType(cp);
            if (Character.isISOControl(cp) || type == Character.FORMAT || type == Character.LINE_SEPARATOR
                    || type == Character.PARAGRAPH_SEPARATOR || type == Character.UNASSIGNED
                    || type == Character.PRIVATE_USE || type == Character.SURROGATE) {
                sb.append(' ');
            } else {
                sb.appendCodePoint(cp);
            }
        });
        String collapsed = WHITESPACE.matcher(sb).replaceAll(" ").strip();
        return cap(collapsed, max);
    }

    /** The URL when it is a plain http(s) URL (re-checked; nothing else is printed), else null. */
    static String url(String value) {
        String u = value == null ? "" : value.strip();
        if (u.isEmpty() || u.length() > MAX_URL || !HTTP_URL.matcher(u).matches()
                || u.codePoints().anyMatch(cp -> Character.isISOControl(cp) || Character.isWhitespace(cp))) {
            return null;
        }
        return u;
    }

    static String workplaceWord(String workplace) {
        if (workplace == null) {
            return "workplace not stated";
        }
        return switch (workplace.strip().toUpperCase(Locale.ROOT)) {
            case "REMOTE" -> "Remote";
            case "HYBRID" -> "Hybrid";
            case "ONSITE" -> "On-site";
            default -> "workplace not stated";
        };
    }

    /** Flag names in words: {@code REMOTE_ELIGIBILITY_UNKNOWN} → "remote eligibility unknown". */
    static List<String> flagWords(List<String> flags) {
        if (flags == null || flags.isEmpty()) {
            return List.of();
        }
        Set<String> out = new LinkedHashSet<>();
        for (String f : flags) {
            String word = clean(f, 60).replace('_', ' ').toLowerCase(Locale.ROOT).strip();
            if (!word.isEmpty()) {
                out.add(word);
            }
        }
        return List.copyOf(out);
    }

    static String relative(Instant posted, Instant now) {
        Duration age = Duration.between(posted, now);
        if (age.isNegative() || age.toMinutes() < 1) {
            return "just now";
        }
        long minutes = age.toMinutes();
        if (minutes < 60) {
            return minutes + (minutes == 1 ? " minute ago" : " minutes ago");
        }
        long hours = age.toHours();
        if (hours < 48) {
            return hours + (hours == 1 ? " hour ago" : " hours ago");
        }
        long days = age.toDays();
        return days + " days ago";
    }

    private static String location(NotificationContent c) {
        String text = clean(c.locationText(), MAX_LOCATION);
        if (!text.isEmpty()) {
            return text;
        }
        List<String> codes = new ArrayList<>();
        for (String code : c.countryCodes()) {
            String cc = clean(code, 3);
            if (!cc.isEmpty()) {
                codes.add(cc);
            }
        }
        return cap(String.join(", ", codes), MAX_LOCATION);
    }

    private static String skills(List<String> names, int max) {
        List<String> cleaned = new ArrayList<>();
        for (String n : names) {
            String s = clean(n, MAX_SKILL);
            if (!s.isEmpty()) {
                cleaned.add(s);
            }
        }
        if (cleaned.isEmpty()) {
            return "none";
        }
        String shown = String.join(", ", cleaned.subList(0, Math.min(max, cleaned.size())));
        return cleaned.size() > max ? shown + " (+" + (cleaned.size() - max) + " more)" : shown;
    }

    private static String sources(List<String> kinds) {
        Set<String> out = new LinkedHashSet<>();
        for (String k : kinds) {
            String s = clean(k, 40);
            if (!s.isEmpty()) {
                out.add(s);
            }
        }
        return out.isEmpty() ? "job feed" : String.join(", ", out);
    }

    private static int percent(double value) {
        if (Double.isNaN(value)) {
            return 0;
        }
        return (int) Math.round(Math.max(0.0, Math.min(1.0, value)) * 100d);
    }

    private static String capBody(String body) {
        return cap(body, MAX_BODY);
    }

    /** At most {@code max} chars; a cut value ends with {@code …} (counted in {@code max}). */
    static String cap(String value, int max) {
        if (value.length() <= max) {
            return value;
        }
        if (max <= 1) {
            return ELLIPSIS;
        }
        int end = max - 1;
        if (Character.isHighSurrogate(value.charAt(end - 1))) {
            end--;                                              // never split a surrogate pair
        }
        return value.substring(0, end).stripTrailing() + ELLIPSIS;
    }

    private static String orDefault(String value, String fallback) {
        return value == null || value.isEmpty() ? fallback : value;
    }
}
