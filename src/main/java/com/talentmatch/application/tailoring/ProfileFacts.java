package com.talentmatch.application.tailoring;

import com.talentmatch.ai.PromptText;
import com.talentmatch.application.content.Header;
import com.talentmatch.application.content.LinkItem;
import com.talentmatch.profile.ProfileDocument;
import com.talentmatch.profile.ProfileDocument.Certification;
import com.talentmatch.profile.ProfileDocument.Education;
import com.talentmatch.profile.ProfileDocument.Experience;
import com.talentmatch.profile.ProfileDocument.Language;
import com.talentmatch.profile.ProfileDocument.Link;
import com.talentmatch.profile.ProfileDocument.Project;
import com.talentmatch.profile.ProfileDocument.SkillEntry;
import com.talentmatch.profile.ProfileText;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One profile version prepared for tailoring (§4.2): stable refs, the PROFILE FACTS prompt block and the
 * allowed sets the grounding checks use. Pure and immutable after {@link #of}.
 *
 * <p><b>Refs</b> follow profile list order: experience {@code E1..En} (most recent first, as stored) with
 * highlights {@code E{i}.H{j}}; projects {@code P{i}} with {@code P{i}.H{j}}; certifications {@code C{i}};
 * education {@code D{i}}. Skills are referenced by their profile name.
 *
 * <p><b>Prompt block</b> (decision q): never the name, email, phone, location, links, credential ids or
 * certificate URLs. Every text goes through {@link PromptText}. At most {@code maxChars} characters: every
 * header line is always included; highlights are added newest-first (roles in order, then projects) until
 * the next one doesn't fit. Highlights left out are not shown to the model and are not selectable
 * ({@link Highlight#shown()}); {@link #truncated()} reports it.
 */
public final class ProfileFacts {

    public static final String BLOCK_HEADER = "PROFILE FACTS (authoritative: the only facts you may use)";
    private static final String NONE = "(none)";
    private static final String TAG_OPEN = "<profile_text>";
    private static final String TAG_CLOSE = "</profile_text>";
    private static final Pattern YEAR = Pattern.compile("(?:19|20)\\d{2}");
    private static final Pattern DATE = Pattern.compile("^(\\d{4})(?:-(\\d{2}))?$");

    /**
     * One profile highlight.
     *
     * @param ref       {@code E1.H2} / {@code P1.H1}
     * @param parentRef {@code E1} / {@code P1}
     * @param text      the profile's own wording
     * @param shown     false when it was left out of the prompt by the budget (not selectable)
     */
    public record Highlight(String ref, String parentRef, String text, boolean shown) {
    }

    private final ProfileDocument profile;
    private final List<Experience> experience;
    private final List<Project> projects;
    private final List<Certification> certifications;
    private final List<Education> education;
    private final String promptBlock;
    private final boolean truncated;
    private final List<String> experienceRefs;
    private final List<String> projectRefs;
    private final List<String> certificationRefs;
    private final List<String> educationRefs;
    private final Map<String, Highlight> highlights;
    private final Map<String, List<Highlight>> highlightsByParent;
    private final Map<String, Set<String>> numbersByRef;
    private final Map<String, Set<UUID>> skillsByRef;
    private final Map<String, Set<String>> wordsByRef;
    private final Set<String> statedNumbers;
    private final Integer careerSpanYears;
    private final Set<String> roleDurations;
    private final Set<String> allowedYears;
    private final Set<UUID> skillsGlobal;
    private final Set<String> profileWords;
    private final Set<String> capitalisedAllowed;
    private final Map<String, String> skillByLowerName;
    private final Map<UUID, String> skillById;

    private ProfileFacts(Builder b) {
        this.profile = b.profile;
        this.experience = b.experience;
        this.projects = b.projects;
        this.certifications = b.certifications;
        this.education = b.education;
        this.promptBlock = b.promptBlock;
        this.truncated = b.truncated;
        this.experienceRefs = List.copyOf(b.experienceRefs);
        this.projectRefs = List.copyOf(b.projectRefs);
        this.certificationRefs = List.copyOf(b.certificationRefs);
        this.educationRefs = List.copyOf(b.educationRefs);
        this.highlights = Collections.unmodifiableMap(new LinkedHashMap<>(b.highlights));
        Map<String, List<Highlight>> byParent = new LinkedHashMap<>();
        b.highlights.values().forEach(h -> byParent.computeIfAbsent(h.parentRef(), k -> new ArrayList<>()).add(h));
        byParent.replaceAll((k, v) -> List.copyOf(v));
        this.highlightsByParent = Collections.unmodifiableMap(byParent);
        this.numbersByRef = freeze(b.numbersByRef);
        this.skillsByRef = freeze(b.skillsByRef);
        this.wordsByRef = freeze(b.wordsByRef);
        this.statedNumbers = Set.copyOf(b.statedNumbers);
        this.careerSpanYears = b.careerSpanYears;
        this.roleDurations = Set.copyOf(b.roleDurations);
        this.allowedYears = Set.copyOf(b.allowedYears);
        this.skillsGlobal = Set.copyOf(b.skillsGlobal);
        this.profileWords = Set.copyOf(b.profileWords);
        this.capitalisedAllowed = Set.copyOf(b.capitalisedAllowed);
        this.skillByLowerName = Map.copyOf(b.skillByLowerName);
        this.skillById = Map.copyOf(b.skillById);
    }

    /**
     * @param profile  the profile version (normalized, as stored)
     * @param maxChars the prompt block budget ({@code talentmatch.applications.generation.max-profile-chars})
     * @param check    the vocabulary for skill sets (without one, the skill sets are empty and the checks
     *                 fail closed)
     * @param today    end of current roles, for the career span and role durations
     */
    public static ProfileFacts of(ProfileDocument profile, int maxChars, FactCheck check, LocalDate today) {
        if (profile == null) {
            throw new IllegalArgumentException("profile is required");
        }
        if (maxChars < 1) {
            throw new IllegalArgumentException("maxChars must be positive");
        }
        Builder b = new Builder(profile, check == null ? FactCheck.unavailable() : check,
                today == null ? LocalDate.now() : today);
        b.refs();
        b.block(maxChars);
        b.allowedSets();
        return new ProfileFacts(b);
    }

    // ------------------------------------------------------------------ accessors

    public ProfileDocument profile() {
        return profile;
    }

    /** The PROFILE FACTS block of the user message (§4.5), starting with {@link #BLOCK_HEADER}, no trailing newline. */
    public String promptBlock() {
        return promptBlock;
    }

    /** True when some highlights were left out of the prompt by the budget ({@code PROFILE_TRUNCATED}). */
    public boolean truncated() {
        return truncated;
    }

    /** The profile's roles (null entries skipped); index i has ref {@code E(i+1)}. */
    public List<Experience> experience() {
        return experience;
    }

    /** The profile's projects; index i has ref {@code P(i+1)}. */
    public List<Project> projects() {
        return projects;
    }

    /** The profile's certifications; index i has ref {@code C(i+1)}. */
    public List<Certification> certifications() {
        return certifications;
    }

    /** The profile's education entries; index i has ref {@code D(i+1)}. */
    public List<Education> education() {
        return education;
    }

    /** The profile's languages (null entries skipped). */
    public List<Language> languages() {
        return nonNull(profile.languages());
    }

    public List<String> experienceRefs() {
        return experienceRefs;
    }

    public List<String> projectRefs() {
        return projectRefs;
    }

    public List<String> certificationRefs() {
        return certificationRefs;
    }

    public List<String> educationRefs() {
        return educationRefs;
    }

    /** The highlight with this ref (shown or not), or null. */
    public Highlight highlight(String ref) {
        return ref == null ? null : highlights.get(ref);
    }

    /** The highlights of a role or project ({@code E1}, {@code P1}) in profile order (shown or not). */
    public List<Highlight> highlightsOf(String parentRef) {
        return highlightsByParent.getOrDefault(parentRef, List.of());
    }

    /** Index of a ref in its list ({@code E2} → 1), or -1 when it isn't one of this profile's refs. */
    public int indexOf(String ref) {
        if (ref == null || ref.isEmpty()) {
            return -1;
        }
        List<String> refs = switch (ref.charAt(0)) {
            case 'E' -> experienceRefs;
            case 'P' -> projectRefs;
            case 'C' -> certificationRefs;
            case 'D' -> educationRefs;
            default -> List.of();
        };
        return refs.indexOf(ref);
    }

    /** Canonical numbers allowed in a highlight's text: its own, plus its role's or project's technologies. */
    public Set<String> numbers(String highlightRef) {
        return numbersByRef.getOrDefault(highlightRef, Set.of());
    }

    /** Skills allowed in a highlight's text: its own mentions, plus its role's or project's technologies. */
    public Set<UUID> skills(String highlightRef) {
        return skillsByRef.getOrDefault(highlightRef, Set.of());
    }

    /** Content words of a highlight and of its role (title, company, technologies) or project. */
    public Set<String> words(String highlightRef) {
        return wordsByRef.getOrDefault(highlightRef, Set.of());
    }

    /**
     * Every number stated in the profile's text fields, the years of every date and every skill's years
     * (no derived durations). Allowed in the headline.
     */
    public Set<String> statedNumbers() {
        return statedNumbers;
    }

    /** {@link #statedNumbers()} plus the career span. Allowed in the summary. */
    public Set<String> summaryNumbers() {
        if (careerSpanYears == null) {
            return statedNumbers;
        }
        Set<String> out = new HashSet<>(statedNumbers);
        out.add(Integer.toString(careerSpanYears));
        return Set.copyOf(out);
    }

    /** {@code numbersGlobal}: stated numbers, the career span and each role's duration. Allowed in a letter. */
    public Set<String> numbersGlobal() {
        Set<String> out = new HashSet<>(summaryNumbers());
        out.addAll(roleDurations);
        return Set.copyOf(out);
    }

    /** Floor of the years from the earliest role start to the latest end (or today); null without dates. */
    public Integer careerSpanYears() {
        return careerSpanYears;
    }

    /** Floor of each dated role's duration in years (only positive values). */
    public Set<String> roleDurations() {
        return roleDurations;
    }

    /** Years that may appear in a letter: the years of every date, and years written in the profile's text. */
    public Set<String> allowedYears() {
        return allowedYears;
    }

    /** The profile's skills, technologies and every vocabulary mention anywhere in the profile. */
    public Set<UUID> skillsGlobal() {
        return skillsGlobal;
    }

    /** Content words (4+ letters) of the whole profile. */
    public Set<String> profileWords() {
        return profileWords;
    }

    /** Lower-case letter tokens of every profile field ({@link FactCheck#nameTokens}). */
    public Set<String> capitalisedAllowed() {
        return capitalisedAllowed;
    }

    /** The profile's skill names, in profile order. */
    public List<String> skillNames() {
        return nonNull(profile.skills()).stream().map(SkillEntry::name).filter(n -> n != null && !n.isBlank()).toList();
    }

    /**
     * The profile's spelling of a skill: matched case-insensitively against the profile's names, then by
     * vocabulary alias to a profile skill.
     */
    public Optional<String> profileSkill(String name, FactCheck check) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        String direct = skillByLowerName.get(name.strip().toLowerCase(Locale.ROOT));
        if (direct != null) {
            return Optional.of(direct);
        }
        if (check == null) {
            return Optional.empty();
        }
        return check.resolve(name).map(skillById::get);
    }

    /** The contact header from the profile (never sent to the model). */
    public Header header() {
        List<LinkItem> links = new ArrayList<>();
        for (Link l : nz(profile.links())) {
            if (l != null) {
                links.add(new LinkItem(l.label(), l.url()));
            }
        }
        return new Header(profile.fullName(), profile.email(), profile.phone(), profile.location(), links);
    }

    // ------------------------------------------------------------------ building

    private static final class Builder {

        private final ProfileDocument profile;
        private final FactCheck check;
        private final LocalDate today;
        private final List<Experience> experience;
        private final List<Project> projects;
        private final List<Certification> certifications;
        private final List<Education> education;

        private String promptBlock;
        private boolean truncated;
        private final List<String> experienceRefs = new ArrayList<>();
        private final List<String> projectRefs = new ArrayList<>();
        private final List<String> certificationRefs = new ArrayList<>();
        private final List<String> educationRefs = new ArrayList<>();
        /** All highlights in priority order: roles in order, then projects. */
        private final Map<String, Highlight> highlights = new LinkedHashMap<>();
        private final Map<String, Set<String>> numbersByRef = new HashMap<>();
        private final Map<String, Set<UUID>> skillsByRef = new HashMap<>();
        private final Map<String, Set<String>> wordsByRef = new HashMap<>();
        private final Set<String> statedNumbers = new HashSet<>();
        private Integer careerSpanYears;
        private final Set<String> roleDurations = new HashSet<>();
        private final Set<String> allowedYears = new HashSet<>();
        private final Set<UUID> skillsGlobal = new HashSet<>();
        private final Set<String> profileWords = new HashSet<>();
        private final Set<String> capitalisedAllowed = new HashSet<>();
        private final Map<String, String> skillByLowerName = new HashMap<>();
        private final Map<UUID, String> skillById = new HashMap<>();

        Builder(ProfileDocument profile, FactCheck check, LocalDate today) {
            this.profile = profile;
            this.check = check;
            this.today = today;
            this.experience = nonNull(profile.experience());
            this.projects = nonNull(profile.projects());
            this.certifications = nonNull(profile.certifications());
            this.education = nonNull(profile.education());
        }

        void refs() {
            for (int i = 0; i < experience.size(); i++) {
                String ref = "E" + (i + 1);
                experienceRefs.add(ref);
                List<String> hs = nz(experience.get(i).highlights());
                for (int j = 0; j < hs.size(); j++) {
                    if (hs.get(j) != null) {
                        highlights.put(ref + ".H" + (j + 1), new Highlight(ref + ".H" + (j + 1), ref, hs.get(j), true));
                    }
                }
            }
            for (int i = 0; i < projects.size(); i++) {
                String ref = "P" + (i + 1);
                projectRefs.add(ref);
                List<String> hs = nz(projects.get(i).highlights());
                for (int j = 0; j < hs.size(); j++) {
                    if (hs.get(j) != null) {
                        highlights.put(ref + ".H" + (j + 1), new Highlight(ref + ".H" + (j + 1), ref, hs.get(j), true));
                    }
                }
            }
            for (int i = 0; i < certifications.size(); i++) {
                certificationRefs.add("C" + (i + 1));
            }
            for (int i = 0; i < education.size(); i++) {
                educationRefs.add("D" + (i + 1));
            }
        }

        /** Decides which highlights fit the budget, then renders the block with them. */
        void block(int maxChars) {
            int length = render(Set.of()).length();
            Set<String> shown = new HashSet<>();
            for (Highlight h : highlights.values()) {
                int add = highlightLine(h).length() + 1;       // + the line break before it
                if (length + add > maxChars) {
                    break;
                }
                length += add;
                shown.add(h.ref());
            }
            truncated = shown.size() < highlights.size();
            if (truncated) {
                highlights.replaceAll((ref, h) -> shown.contains(ref) ? h
                        : new Highlight(h.ref(), h.parentRef(), h.text(), false));
            }
            promptBlock = render(shown);
        }

        private String render(Set<String> shown) {
            List<String> lines = new ArrayList<>();
            lines.add(BLOCK_HEADER);
            lines.add("HEADLINE: " + tagged(profile.headline()));
            lines.add("SUMMARY: " + tagged(profile.summary()));
            lines.add("SKILLS: " + skillsLine());
            lines.add("EXPERIENCE");
            if (experience.isEmpty()) {
                lines.add(NONE);
            }
            for (int i = 0; i < experience.size(); i++) {
                Experience e = experience.get(i);
                String ref = experienceRefs.get(i);
                lines.add("[" + ref + "] " + join(" | ", PromptText.line(e.title()), PromptText.line(e.company()),
                        dates(e.startDate(), e.endDate(), Boolean.TRUE.equals(e.current())),
                        technologies(e.technologies())));
                addHighlights(lines, ref, shown);
            }
            lines.add("PROJECTS");
            if (projects.isEmpty()) {
                lines.add(NONE);
            }
            for (int i = 0; i < projects.size(); i++) {
                Project p = projects.get(i);
                String ref = projectRefs.get(i);
                String description = PromptText.line(p.description());
                lines.add("[" + ref + "] " + join(" | ", PromptText.line(p.name()), technologies(p.technologies()),
                        description.isEmpty() ? "" : TAG_OPEN + description + TAG_CLOSE));
                addHighlights(lines, ref, shown);
            }
            lines.add("CERTIFICATIONS");
            if (certifications.isEmpty()) {
                lines.add(NONE);
            }
            for (int i = 0; i < certifications.size(); i++) {
                Certification c = certifications.get(i);
                lines.add("[" + certificationRefs.get(i) + "] " + join(" | ", PromptText.line(c.name()),
                        PromptText.line(c.issuer()), PromptText.line(c.issued())));
            }
            lines.add("EDUCATION (always included; for context)");
            if (education.isEmpty()) {
                lines.add(NONE);
            }
            for (int i = 0; i < education.size(); i++) {
                Education d = education.get(i);
                lines.add("[" + educationRefs.get(i) + "] " + join(" | ",
                        join(", ", PromptText.line(d.qualification()), PromptText.line(d.field())),
                        PromptText.line(d.institution()), dates(d.startDate(), d.endDate(), false)));
            }
            lines.add("LANGUAGES: " + languagesLine());
            return String.join("\n", lines);
        }

        private void addHighlights(List<String> lines, String parentRef, Set<String> shown) {
            for (Highlight h : highlights.values()) {
                if (h.parentRef().equals(parentRef) && shown.contains(h.ref())) {
                    lines.add(highlightLine(h));
                }
            }
        }

        private static String highlightLine(Highlight h) {
            return "  [" + h.ref() + "] " + TAG_OPEN + PromptText.line(h.text()) + TAG_CLOSE;
        }

        private static String tagged(String text) {
            String t = PromptText.line(text);
            return TAG_OPEN + (t.isEmpty() ? NONE : t) + TAG_CLOSE;
        }

        private String skillsLine() {
            List<String> out = new ArrayList<>();
            for (SkillEntry s : nonNull(profile.skills())) {
                String name = PromptText.line(s.name());
                if (name.isEmpty()) {
                    continue;
                }
                Integer years = s.years();
                out.add(years == null ? name : name + " (" + years + (years == 1 ? " year)" : " years)"));
            }
            return out.isEmpty() ? NONE : String.join("; ", out);
        }

        private String languagesLine() {
            List<String> out = new ArrayList<>();
            for (Language l : nonNull(profile.languages())) {
                String name = PromptText.line(l.name());
                if (name.isEmpty()) {
                    continue;
                }
                String level = PromptText.line(l.level());
                out.add(level.isEmpty() ? name : name + " (" + level + ")");
            }
            return out.isEmpty() ? NONE : String.join("; ", out);
        }

        private static String technologies(List<String> technologies) {
            List<String> out = new ArrayList<>();
            for (String t : nz(technologies)) {
                String v = PromptText.line(t);
                if (!v.isEmpty()) {
                    out.add(v);
                }
            }
            return out.isEmpty() ? "" : "technologies: " + String.join(", ", out);
        }

        /** "2019-03 to present", "2015 to 2018", "unknown to 2018"; empty when there is no date at all. */
        private static String dates(String start, String end, boolean current) {
            String s = PromptText.line(start);
            String e = current ? "present" : PromptText.line(end);
            if (s.isEmpty() && e.isEmpty()) {
                return "";
            }
            return (s.isEmpty() ? "unknown" : s) + " to " + (e.isEmpty() ? "unknown" : e);
        }

        private static String join(String separator, String... parts) {
            List<String> out = new ArrayList<>();
            for (String p : parts) {
                if (p != null && !p.isEmpty()) {
                    out.add(p);
                }
            }
            return String.join(separator, out);
        }

        // -------------------------------------------------------------- allowed sets

        void allowedSets() {
            List<String> allText = new ArrayList<>();        // every non-contact text field

            text(allText, profile.headline());
            text(allText, profile.summary());
            for (SkillEntry s : nonNull(profile.skills())) {
                if (s.name() == null) {
                    continue;
                }
                text(allText, s.name());
                if (s.years() != null) {
                    statedNumbers.add(Integer.toString(s.years()));
                }
                skillByLowerName.putIfAbsent(s.name().strip().toLowerCase(Locale.ROOT), s.name());
                check.resolve(s.name()).ifPresent(id -> {
                    skillsGlobal.add(id);
                    skillById.putIfAbsent(id, s.name());
                });
            }

            Integer firstStart = null;
            Integer lastEnd = null;
            for (int i = 0; i < experience.size(); i++) {
                Experience e = experience.get(i);
                String ref = experienceRefs.get(i);
                List<String> tech = nz(e.technologies());
                Set<String> parentWords = new HashSet<>();
                for (String field : concat(List.of(nzs(e.title()), nzs(e.company())), tech)) {
                    parentWords.addAll(ProfileText.contentWords(field));
                }
                text(allText, e.title());
                text(allText, e.company());
                text(allText, e.location());
                tech.forEach(t -> text(allText, t));
                parent(ref, tech, parentWords);
                dateYear(e.startDate());
                dateYear(e.endDate());

                Integer start = month(e.startDate());
                Integer end = Boolean.TRUE.equals(e.current()) ? Integer.valueOf(today.getYear() * 12
                        + today.getMonthValue() - 1) : month(e.endDate());
                if (start != null) {
                    firstStart = firstStart == null ? start : Math.min(firstStart, start);
                    int last = end == null ? start : end;
                    lastEnd = lastEnd == null ? last : Math.max(lastEnd, last);
                    if (end != null && (end - start) / 12 > 0) {
                        roleDurations.add(Integer.toString((end - start) / 12));
                    }
                }
            }
            if (firstStart != null && lastEnd != null && lastEnd >= firstStart) {
                careerSpanYears = (lastEnd - firstStart) / 12;
            }
            for (int i = 0; i < projects.size(); i++) {
                Project p = projects.get(i);
                String ref = projectRefs.get(i);
                List<String> tech = nz(p.technologies());
                Set<String> parentWords = new HashSet<>();
                for (String field : concat(List.of(nzs(p.name()), nzs(p.description())), tech)) {
                    parentWords.addAll(ProfileText.contentWords(field));
                }
                text(allText, p.name());
                text(allText, p.description());
                tech.forEach(t -> text(allText, t));
                parent(ref, tech, parentWords);
            }
            for (Highlight h : highlights.values()) {
                text(allText, h.text());
            }
            for (Certification c : certifications) {
                text(allText, c.name());
                text(allText, c.issuer());
                dateYear(c.issued());
                dateYear(c.expires());
            }
            for (Education d : education) {
                text(allText, d.institution());
                text(allText, d.qualification());
                text(allText, d.field());
                dateYear(d.startDate());
                dateYear(d.endDate());
            }
            for (Language l : nonNull(profile.languages())) {
                text(allText, l.name());
                text(allText, l.level());
            }

            for (String t : allText) {
                statedNumbers.addAll(ProfileText.wholeNumbers(t));
                statedNumbers.addAll(ProfileText.numberWordValues(t));
                profileWords.addAll(ProfileText.contentWords(t));
                capitalisedAllowed.addAll(FactCheck.nameTokens(t));
                Matcher m = ProfileText.NUMBER.matcher(t);
                while (m.find()) {
                    String n = ProfileText.canonicalNumber(m.group());
                    if (YEAR.matcher(n).matches()) {
                        allowedYears.add(n);
                    }
                }
            }
            // the name and contact fields are allowed capitalised words, but never numbers or skills
            for (String t : contactTexts()) {
                capitalisedAllowed.addAll(FactCheck.nameTokens(t));
            }
            skillsGlobal.addAll(check.mentions(String.join("\n\n", allText)));
        }

        /** Per-highlight allowed sets for the highlights of one role or project. */
        private void parent(String parentRef, List<String> technologies, Set<String> parentWords) {
            Set<String> techNumbers = new HashSet<>();
            Set<UUID> techSkills = new HashSet<>();
            for (String t : technologies) {
                if (t == null) {
                    continue;
                }
                techNumbers.addAll(ProfileText.wholeNumbers(t));
                check.resolve(t).ifPresent(techSkills::add);
                techSkills.addAll(check.mentions(t));
            }
            skillsGlobal.addAll(techSkills);
            for (Highlight h : highlights.values()) {
                if (!h.parentRef().equals(parentRef)) {
                    continue;
                }
                Set<String> numbers = new HashSet<>(techNumbers);
                numbers.addAll(ProfileText.wholeNumbers(h.text()));
                numbers.addAll(ProfileText.numberWordValues(h.text()));
                numbersByRef.put(h.ref(), numbers);
                Set<UUID> skills = new HashSet<>(techSkills);
                skills.addAll(check.mentions(h.text()));
                skillsByRef.put(h.ref(), skills);
                Set<String> words = new HashSet<>(parentWords);
                words.addAll(ProfileText.contentWords(h.text()));
                wordsByRef.put(h.ref(), words);
            }
        }

        private void dateYear(String date) {
            if (date != null && date.length() >= 4 && YEAR.matcher(date.substring(0, 4)).matches()) {
                statedNumbers.add(date.substring(0, 4));
                allowedYears.add(date.substring(0, 4));
            }
        }

        private List<String> contactTexts() {
            List<String> out = new ArrayList<>();
            out.add(nzs(profile.fullName()));
            out.add(nzs(profile.location()));
            for (Link l : nonNull(profile.links())) {
                out.add(nzs(l.label()));
            }
            return out;
        }

        private static void text(List<String> out, String value) {
            if (value != null && !value.isBlank()) {
                out.add(value);
            }
        }

        /** Months since year 0 for "YYYY-MM" or "YYYY" (January), or null. */
        private static Integer month(String date) {
            if (date == null) {
                return null;
            }
            Matcher m = DATE.matcher(date.strip());
            if (!m.matches()) {
                return null;
            }
            int year = Integer.parseInt(m.group(1));
            int month = m.group(2) == null ? 1 : Integer.parseInt(m.group(2));
            return year * 12 + month - 1;
        }

        private static List<String> concat(List<String> a, List<String> b) {
            List<String> out = new ArrayList<>(a);
            for (String s : b) {
                if (s != null) {
                    out.add(s);
                }
            }
            return out;
        }
    }

    // ------------------------------------------------------------------ helpers

    private static <T> List<T> nonNull(List<T> list) {
        if (list == null) {
            return List.of();
        }
        return list.stream().filter(x -> x != null).toList();
    }

    private static <T> List<T> nz(List<T> list) {
        return list == null ? List.of() : list;
    }

    private static String nzs(String s) {
        return s == null ? "" : s;
    }

    private static <T> Map<String, Set<T>> freeze(Map<String, Set<T>> map) {
        Map<String, Set<T>> out = new HashMap<>();
        map.forEach((k, v) -> out.put(k, Set.copyOf(v)));
        return Collections.unmodifiableMap(out);
    }
}
