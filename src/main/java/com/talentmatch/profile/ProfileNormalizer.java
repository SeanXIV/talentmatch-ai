package com.talentmatch.profile;

import com.talentmatch.profile.ProfileDocument.Certification;
import com.talentmatch.profile.ProfileDocument.Education;
import com.talentmatch.profile.ProfileDocument.Experience;
import com.talentmatch.profile.ProfileDocument.Language;
import com.talentmatch.profile.ProfileDocument.Link;
import com.talentmatch.profile.ProfileDocument.Project;
import com.talentmatch.profile.ProfileDocument.SkillEntry;
import com.talentmatch.service.SkillResolver;
import com.talentmatch.service.TextNormalizer;
import java.time.Clock;
import java.time.Year;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Cleans a {@link ProfileDocument}: trims text, drops empty entries, normalizes dates to
 * {@code YYYY-MM}/{@code YYYY}, and enforces list and length limits.
 *
 * <p>{@link Mode#LENIENT} (AI drafts) fixes what it can and reports each fix as an issue (shown
 * to the owner as a warning). {@link Mode#STRICT} (the owner's PUT) keeps the value and reports
 * an issue for anything invalid (returned as field errors). Pure: no I/O.
 *
 * <p>LENIENT also turns placeholder answers ("N/A", "Not provided", "-", …) into null, because
 * a model sometimes writes them instead of null. Issue paths use the index in the normalized
 * output (what the owner sees in the stored draft); issues about a dropped entry, or about a
 * list that was cut to its limit, use the bare list path without an index. STRICT never drops
 * an entry (an empty one is an issue), so its indices are also the request's indices.
 */
public final class ProfileNormalizer {

    public enum Mode { LENIENT, STRICT }

    /** One problem found while normalizing. */
    public record Issue(String path, String value, String message) {
    }

    /** The cleaned document and the problems found. */
    public record Result(ProfileDocument document, List<Issue> issues) {
    }

    public static final int MAX_NAME = 200;
    public static final int MAX_LINE = 300;
    public static final int MAX_TEXT = 2000;
    public static final int MAX_SUMMARY = 5000;
    public static final int MAX_URL = 500;
    public static final int MAX_EXPERIENCE = 50;
    public static final int MAX_PROJECTS = 50;
    public static final int MAX_SKILLS = 100;
    public static final int MAX_CERTIFICATIONS = 50;
    public static final int MAX_EDUCATION = 20;
    public static final int MAX_LANGUAGES = 20;
    public static final int MAX_LINKS = 20;
    public static final int MAX_BULLETS = 30;
    public static final int MAX_YEARS = 60;

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final Pattern HORIZONTAL_SPACE = Pattern.compile("[\\t\\x0B\\f\\r ]+");
    private static final Pattern BLANK_LINES = Pattern.compile("\\n{3,}");
    private static final Pattern Y = Pattern.compile("^(\\d{4})$");
    private static final Pattern Y_M = Pattern.compile("^(\\d{4})[-/.](\\d{1,2})$");
    private static final Pattern M_Y = Pattern.compile("^(\\d{1,2})[-/.](\\d{4})$");
    private static final Pattern MONTH_Y = Pattern.compile("^([A-Za-z]{3,9})\\.?,?\\s+(\\d{4})$");
    private static final Pattern PRESENT = Pattern.compile("^(present|current|now|ongoing|to date|today)$",
            Pattern.CASE_INSENSITIVE);
    private static final Map<String, Integer> MONTHS = months();
    private static final Pattern SKILL_PATH = Pattern.compile("^skills\\[(\\d+)](.*)$");
    /** Things a model writes instead of null; compared lower-case, trailing dots ignored. */
    private static final Set<String> PLACEHOLDERS = Set.of("n/a", "n.a", "na", "none", "null", "nil", "unknown",
            "not provided", "not specified", "not stated", "not available", "not applicable", "-", "--", "—", "–");

    private final Mode mode;
    private final int maxYear;
    private final List<Issue> issues = new ArrayList<>();

    private ProfileNormalizer(Mode mode, Clock clock) {
        this.mode = mode;
        this.maxYear = Year.now(clock).getValue() + 10;
    }

    public static Result normalize(ProfileDocument raw, Mode mode) {
        return normalize(raw, mode, Clock.systemUTC());
    }

    /** @param clock today's date decides the latest accepted year (now + 10) */
    public static Result normalize(ProfileDocument raw, Mode mode, Clock clock) {
        ProfileNormalizer n = new ProfileNormalizer(mode, clock);
        ProfileDocument doc = n.document(raw == null ? empty() : raw);
        return new Result(doc, List.copyOf(n.issues));
    }

    /** True for "N/A", "none", "Not provided", "-" and similar non-answers (any case). */
    public static boolean isPlaceholder(String value) {
        if (value == null) {
            return false;
        }
        String v = value.strip().toLowerCase(Locale.ROOT);
        while (v.endsWith(".") && v.length() > 1) {
            v = v.substring(0, v.length() - 1);
        }
        return PLACEHOLDERS.contains(v);
    }

    /** At most {@code max} chars, never splitting a surrogate pair (emoji, rare CJK). */
    public static String cut(String s, int max) {
        if (s.length() <= max) {
            return s;
        }
        int end = max;
        if (end > 0 && Character.isHighSurrogate(s.charAt(end - 1))) {
            end--;
        }
        return s.substring(0, end);
    }

    public static ProfileDocument empty() {
        return new ProfileDocument(null, null, null, null, null, null, List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of());
    }

    // ------------------------------------------------------------------ document

    private ProfileDocument document(ProfileDocument d) {
        String fullName = line("fullName", d.fullName(), MAX_NAME);
        String email = TextNormalizer.email(lenientValue(d.email()));
        if (email != null && !TextNormalizer.isValidEmail(email)) {
            issue("email", email, "'" + email + "' is not a valid email address. Use a format like ada@example.com.");
            if (mode == Mode.LENIENT) {
                email = null;
            }
        }
        String phone = line("phone", d.phone(), 50);
        String location = line("location", d.location(), MAX_LINE);
        String headline = line("headline", d.headline(), MAX_LINE);
        String summary = text("summary", d.summary(), MAX_SUMMARY);

        List<Link> links = list("links", d.links(), MAX_LINKS, this::link);
        List<Experience> experience = list("experience", d.experience(), MAX_EXPERIENCE, this::experience);
        List<Project> projects = list("projects", d.projects(), MAX_PROJECTS, this::project);
        List<SkillEntry> skills = dedupSkills(list("skills", d.skills(), MAX_SKILLS, this::skill));
        List<Certification> certifications = list("certifications", d.certifications(), MAX_CERTIFICATIONS,
                this::certification);
        List<Education> education = list("education", d.education(), MAX_EDUCATION, this::education);
        List<Language> languages = list("languages", d.languages(), MAX_LANGUAGES, this::language);

        return new ProfileDocument(fullName, email, phone, location, headline, summary, links, experience,
                projects, skills, certifications, education, languages);
    }

    private Link link(String p, Link l) {
        String url = line(p + ".url", l.url(), MAX_URL);
        if (url == null) {
            return null;
        }
        if (WHITESPACE.matcher(url).find()) {
            issue(p + ".url", url, "A URL cannot contain spaces.");
        }
        return new Link(line(p + ".label", l.label(), 100), url);
    }

    private Experience experience(String p, Experience e) {
        String title = line(p + ".title", e.title(), MAX_LINE);
        String company = line(p + ".company", e.company(), MAX_NAME);
        if (title == null && company == null) {
            return null;
        }
        String start = date(p + ".startDate", e.startDate());
        String endRaw = TextNormalizer.text(lenientValue(e.endDate()));
        boolean current = Boolean.TRUE.equals(e.current());
        String end;
        if (endRaw != null && PRESENT.matcher(endRaw).matches()) {
            end = null;
            current = true;
        } else {
            end = date(p + ".endDate", endRaw);
        }
        if (current && end != null) {
            issue(p + ".endDate", end, "A current role has no end date; clear the end date or untick current.");
            if (mode == Mode.LENIENT) {
                end = null;
            }
        }
        checkOrder(p, start, end);
        return new Experience(title, company, line(p + ".location", e.location(), MAX_LINE), start, end, current,
                technologies(p + ".technologies", e.technologies()), bullets(p + ".highlights", e.highlights()));
    }

    private Project project(String p, Project pr) {
        String name = line(p + ".name", pr.name(), MAX_NAME);
        if (name == null) {
            return null;
        }
        return new Project(name, text(p + ".description", pr.description(), MAX_TEXT),
                technologies(p + ".technologies", pr.technologies()), line(p + ".url", pr.url(), MAX_URL),
                bullets(p + ".highlights", pr.highlights()));
    }

    /** Technology names: trimmed, case-insensitively de-duplicated, at most {@link #MAX_BULLETS}. */
    private List<String> technologies(String field, List<String> raw) {
        List<String> tech = new ArrayList<>();
        for (String t : limit(field, nonNull(raw), MAX_BULLETS)) {
            String v = line(field + "[" + tech.size() + "]", t, SkillResolver.MAX_SKILL_NAME_LENGTH);
            if (v != null && tech.stream().noneMatch(x -> x.equalsIgnoreCase(v))) {
                tech.add(v);
            }
        }
        return List.copyOf(tech);
    }

    private SkillEntry skill(String p, SkillEntry s) {
        String name = TextNormalizer.skillName(lenientValue(s.name()));
        if (name == null) {
            return null;
        }
        if (name.length() > SkillResolver.MAX_SKILL_NAME_LENGTH) {
            issue(p + ".name", name, "Skill name must be at most " + SkillResolver.MAX_SKILL_NAME_LENGTH
                    + " characters (got " + name.length() + ").");
            if (mode == Mode.LENIENT) {
                return null;
            }
        }
        Integer years = s.years();
        if (years != null && (years < 0 || years > MAX_YEARS)) {
            issue(p + ".years", String.valueOf(years),
                    "Years of experience must be between 0 and " + MAX_YEARS + " (got " + years + ").");
            if (mode == Mode.LENIENT) {
                years = null;
            }
        }
        return new SkillEntry(name, years);
    }

    private List<SkillEntry> dedupSkills(List<SkillEntry> skills) {
        List<SkillEntry> out = new ArrayList<>();
        Map<String, Integer> firstInput = new HashMap<>();
        Map<String, Integer> outIndex = new HashMap<>();
        int[] newIndex = new int[skills.size()];
        for (int i = 0; i < skills.size(); i++) {
            SkillEntry s = skills.get(i);
            String key = TextNormalizer.skillKey(s.name());
            Integer at = firstInput.putIfAbsent(key, i);
            if (at == null) {
                outIndex.put(key, out.size());
                newIndex[i] = out.size();
                out.add(s);
            } else if (mode == Mode.STRICT) {
                issue("skills[" + i + "].name", s.name(),
                        "Duplicate skill '" + s.name() + "' (already listed at skills[" + at + "]).");
                newIndex[i] = out.size();
                out.add(s);
            } else {
                // keep the first entry, but don't lose a stated number of years
                int idx = outIndex.get(key);
                newIndex[i] = idx;
                SkillEntry kept = out.get(idx);
                if (kept.years() == null && s.years() != null) {
                    out.set(idx, new SkillEntry(kept.name(), s.years()));
                }
            }
        }
        if (mode == Mode.LENIENT && out.size() < skills.size()) {
            repointSkillIssues(newIndex);
        }
        return List.copyOf(out);
    }

    /** After LENIENT de-duplication, skills[i] issues must point at the merged entry's index. */
    private void repointSkillIssues(int[] newIndex) {
        for (int k = 0; k < issues.size(); k++) {
            Issue issue = issues.get(k);
            Matcher m = SKILL_PATH.matcher(issue.path() == null ? "" : issue.path());
            if (m.matches()) {
                int i = Integer.parseInt(m.group(1));
                if (i < newIndex.length) {
                    issues.set(k, new Issue("skills[" + newIndex[i] + "]" + m.group(2), issue.value(), issue.message()));
                }
            }
        }
    }

    private Certification certification(String p, Certification c) {
        String name = line(p + ".name", c.name(), MAX_LINE);
        if (name == null) {
            return null;
        }
        String issued = date(p + ".issued", c.issued());
        String expires = date(p + ".expires", c.expires());
        if (issued != null && expires != null && expires.compareTo(issued) < 0) {
            issue(p + ".expires", expires, "The expiry date is before the issue date.");
        }
        return new Certification(name, line(p + ".issuer", c.issuer(), MAX_NAME), issued, expires,
                line(p + ".credentialId", c.credentialId(), 200), line(p + ".url", c.url(), MAX_URL));
    }

    private Education education(String p, Education e) {
        String institution = line(p + ".institution", e.institution(), MAX_NAME);
        if (institution == null) {
            return null;
        }
        String start = date(p + ".startDate", e.startDate());
        String end = date(p + ".endDate", e.endDate());
        checkOrder(p, start, end);
        return new Education(institution, line(p + ".qualification", e.qualification(), MAX_LINE),
                line(p + ".field", e.field(), MAX_LINE), start, end);
    }

    private Language language(String p, Language l) {
        String name = line(p + ".name", l.name(), 100);
        return name == null ? null : new Language(name, line(p + ".level", l.level(), 100));
    }

    // ------------------------------------------------------------------ helpers

    /**
     * Normalizes each entry and drops nulls and entries the mapper returns null for (empty).
     * Paths use the output index; issues raised while mapping an entry that is then dropped are
     * re-pointed at the bare list path. In STRICT mode a dropped entry is itself an issue at its
     * request index (STRICT output is only used when there are no issues, so indices agree).
     */
    private <T> List<T> list(String field, List<T> raw, int max, BiFunction<String, T, T> mapper) {
        List<T> out = new ArrayList<>();
        List<T> items = limit(field, nonNull(raw), max);
        for (int i = 0; i < items.size(); i++) {
            T item = items.get(i);
            int index = mode == Mode.STRICT ? i : out.size();
            int issuesBefore = issues.size();
            T mapped = item == null ? null : mapper.apply(field + "[" + index + "]", item);
            if (mapped != null) {
                out.add(mapped);
                continue;
            }
            if (mode == Mode.STRICT) {
                issue(field + "[" + i + "]", null, "This entry is empty or has no name; fill it in or remove it.");
            } else {
                for (int k = issuesBefore; k < issues.size(); k++) {
                    Issue dropped = issues.get(k);
                    issues.set(k, new Issue(field, dropped.value(), dropped.message()));
                }
            }
        }
        return List.copyOf(out);
    }

    private <T> List<T> limit(String field, List<T> items, int max) {
        if (items.size() <= max) {
            return items;
        }
        issue(field, null, "At most " + max + " entries are kept (got " + items.size() + ").");
        return items.subList(0, max);
    }

    private List<String> bullets(String field, List<String> raw) {
        List<String> out = new ArrayList<>();
        List<String> items = limit(field, nonNull(raw), MAX_BULLETS);
        for (int i = 0; i < items.size(); i++) {
            String v = text(field + "[" + (mode == Mode.STRICT ? i : out.size()) + "]", items.get(i), 1000);
            if (v != null) {
                out.add(v);
            }
        }
        return List.copyOf(out);
    }

    /** Single line: whitespace collapsed; too long is an issue (lenient: truncated). */
    private String line(String field, String raw, int max) {
        String v = TextNormalizer.text(lenientValue(raw));
        if (v == null) {
            return null;
        }
        v = WHITESPACE.matcher(v).replaceAll(" ");
        return length(field, v, max);
    }

    /** Multi-line text: line breaks kept, runs of spaces and blank lines collapsed. */
    private String text(String field, String raw, int max) {
        String v = TextNormalizer.text(lenientValue(raw));
        if (v == null) {
            return null;
        }
        v = HORIZONTAL_SPACE.matcher(v.replace("\r\n", "\n")).replaceAll(" ");
        v = BLANK_LINES.matcher(v).replaceAll("\n\n").strip();
        return length(field, v, max);
    }

    private String length(String field, String v, int max) {
        if (v.length() <= max) {
            return v;
        }
        issue(field, cut(v, 60) + "…",
                "Must be at most " + max + " characters (got " + v.length() + ")"
                        + (mode == Mode.LENIENT ? "; it was shortened." : "."));
        return mode == Mode.LENIENT ? cut(v, max).strip() : v;
    }

    /** {@code YYYY-MM} or {@code YYYY}; null for blank. Unparseable → issue (lenient: null). */
    String date(String field, String raw) {
        String v = TextNormalizer.text(lenientValue(raw));
        if (v == null) {
            return null;
        }
        String parsed = parseDate(v, maxYear);
        if (parsed == null) {
            issue(field, v, "'" + v + "' is not a date in the form YYYY-MM or YYYY"
                    + (mode == Mode.LENIENT ? "; it was cleared, please re-enter it." : "."));
            return mode == Mode.LENIENT ? null : v;
        }
        return parsed;
    }

    static String parseDate(String v) {
        return parseDate(v, Year.now(Clock.systemUTC()).getValue() + 10);
    }

    static String parseDate(String v, int maxYear) {
        Matcher m;
        int year;
        Integer month = null;
        if ((m = Y.matcher(v)).matches()) {
            year = Integer.parseInt(m.group(1));
        } else if ((m = Y_M.matcher(v)).matches()) {
            year = Integer.parseInt(m.group(1));
            month = Integer.parseInt(m.group(2));
        } else if ((m = M_Y.matcher(v)).matches()) {
            month = Integer.parseInt(m.group(1));
            year = Integer.parseInt(m.group(2));
        } else if ((m = MONTH_Y.matcher(v)).matches()) {
            month = MONTHS.get(m.group(1).toLowerCase(Locale.ROOT));
            if (month == null) {
                return null;
            }
            year = Integer.parseInt(m.group(2));
        } else {
            return null;
        }
        if (year < 1900 || year > maxYear || (month != null && (month < 1 || month > 12))) {
            return null;
        }
        return month == null ? String.valueOf(year) : String.format("%04d-%02d", year, month);
    }

    private void checkOrder(String p, String start, String end) {
        if (start != null && end != null && end.compareTo(start.substring(0, Math.min(start.length(), end.length()))) < 0) {
            issue(p + ".endDate", end, "The end date is before the start date (" + start + ").");
        }
    }

    /** LENIENT: placeholder answers become null (silently: there was nothing to keep). */
    private String lenientValue(String raw) {
        return mode == Mode.LENIENT && isPlaceholder(raw) ? null : raw;
    }

    private void issue(String path, String value, String message) {
        issues.add(new Issue(path, value, message));
    }

    private static <T> List<T> nonNull(List<T> list) {
        return list == null ? List.of() : list;
    }

    private static Map<String, Integer> months() {
        String[] names = {"january", "february", "march", "april", "may", "june", "july", "august", "september",
                "october", "november", "december"};
        Map<String, Integer> m = new HashMap<>();
        for (int i = 0; i < names.length; i++) {
            m.put(names[i], i + 1);
            m.put(names[i].substring(0, 3), i + 1);
        }
        m.put("sept", 9);
        return Map.copyOf(m);
    }
}
