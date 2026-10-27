package com.talentmatch.application.render;

import static com.talentmatch.application.render.RenderText.hasText;
import static com.talentmatch.application.render.RenderText.join;

import com.talentmatch.application.content.Bullet;
import com.talentmatch.application.content.CertificationItem;
import com.talentmatch.application.content.EducationItem;
import com.talentmatch.application.content.ExperienceItem;
import com.talentmatch.application.content.Header;
import com.talentmatch.application.content.LanguageItem;
import com.talentmatch.application.content.LinkItem;
import com.talentmatch.application.content.ProjectItem;
import com.talentmatch.application.content.TailoredCvContent;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The fixed single-column, ATS-friendly CV layout (decision b, §4.8): name, contact line, headline,
 * summary, then Skills, Experience, Projects, Certifications, Education and Languages, each only when
 * it has content. No tables, columns or images. Pure and thread-safe.
 */
public final class CvLayout {

    static final String DOT = " · ";       // " · "
    static final String EM_DASH = " — ";   // " — "
    static final String EN_DASH = " – ";   // " – "
    static final String PRESENT = "Present";

    private static final Pattern YEAR_MONTH = Pattern.compile("(\\d{4})-(\\d{1,2})");
    private static final String[] SHORT_MONTHS = {
            "Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"};

    private CvLayout() {
    }

    public static List<Block> layout(TailoredCvContent cv) {
        List<Block> blocks = new ArrayList<>();
        Header header = cv.header();
        if (header != null && hasText(header.fullName())) {
            blocks.add(new Block.Title(header.fullName().strip()));
        }
        String contact = contactLine(header);
        if (contact != null) {
            blocks.add(new Block.ContactLine(contact));
        }
        if (hasText(cv.headline())) {
            blocks.add(new Block.Subheading(cv.headline().strip(), null));
        }
        if (hasText(cv.summary())) {
            blocks.add(new Block.Paragraph(cv.summary().strip()));
        }

        String skills = join(", ", cv.skills());
        if (skills != null) {
            blocks.add(new Block.Heading("Skills"));
            blocks.add(new Block.Paragraph(skills));
        }

        List<List<Block>> roles = new ArrayList<>();
        for (ExperienceItem role : orEmpty(cv.experience())) {
            List<Block> entry = new ArrayList<>();
            String line = join(EM_DASH, role.title(), role.company());
            String detail = join(DOT, role.location(), range(role.startDate(), role.endDate(), role.current()));
            addEntryLine(entry, line, detail);
            bullets(entry, role.highlights());
            roles.add(entry);
        }
        section(blocks, "Experience", roles);

        List<List<Block>> projects = new ArrayList<>();
        for (ProjectItem project : orEmpty(cv.projects())) {
            List<Block> entry = new ArrayList<>();
            String detail = join(DOT, join(", ", project.technologies()), project.url());
            addEntryLine(entry, hasText(project.name()) ? project.name().strip() : null, detail);
            if (hasText(project.description())) {
                entry.add(new Block.Paragraph(project.description().strip()));
            }
            bullets(entry, project.highlights());
            projects.add(entry);
        }
        section(blocks, "Projects", projects);

        List<Block> certifications = new ArrayList<>();
        for (CertificationItem cert : orEmpty(cv.certifications())) {
            String expires = hasText(cert.expires()) ? "expires " + formatDate(cert.expires()) : null;
            String detail = join(", ", cert.issuer(), formatDate(cert.issued()), expires);
            String text = join(EM_DASH, cert.name(), detail);
            if (text != null) {
                certifications.add(new Block.BulletItem(text));
            }
        }
        if (!certifications.isEmpty()) {
            blocks.add(new Block.Heading("Certifications"));
            blocks.addAll(certifications);
        }

        List<List<Block>> education = new ArrayList<>();
        for (EducationItem edu : orEmpty(cv.education())) {
            List<Block> entry = new ArrayList<>();
            String qualification = join(", ", edu.qualification(), edu.field());
            String range = range(edu.startDate(), edu.endDate(), null);
            if (qualification != null) {
                addEntryLine(entry, qualification, join(DOT, edu.institution(), range));
            } else {
                addEntryLine(entry, hasText(edu.institution()) ? edu.institution().strip() : null, range);
            }
            education.add(entry);
        }
        section(blocks, "Education", education);

        List<String> languages = new ArrayList<>();
        for (LanguageItem language : orEmpty(cv.languages())) {
            if (hasText(language.name())) {
                languages.add(hasText(language.level())
                        ? language.name().strip() + " (" + language.level().strip() + ")"
                        : language.name().strip());
            }
        }
        if (!languages.isEmpty()) {
            blocks.add(new Block.Heading("Languages"));
            blocks.add(new Block.Paragraph(String.join(", ", languages)));
        }
        return List.copyOf(blocks);
    }

    /** "email · phone · location · link urls", or null when the header has none of them. */
    static String contactLine(Header header) {
        if (header == null) {
            return null;
        }
        List<String> parts = new ArrayList<>();
        parts.add(header.email());
        parts.add(header.phone());
        parts.add(header.location());
        for (LinkItem link : orEmpty(header.links())) {
            parts.add(link.url());
        }
        return join(DOT, parts);
    }

    /**
     * A profile date as printed: {@code 2019-03} → "Mar 2019", {@code 2019} → "2019", anything else
     * as written (stripped). Null or blank → null.
     */
    public static String formatDate(String date) {
        if (!hasText(date)) {
            return null;
        }
        String d = date.strip();
        Matcher m = YEAR_MONTH.matcher(d);
        if (m.matches()) {
            int month = Integer.parseInt(m.group(2));
            if (month >= 1 && month <= 12) {
                return SHORT_MONTHS[month - 1] + " " + m.group(1);
            }
        }
        return d;
    }

    /**
     * "{start} – {end|Present}". The end is "Present" when it is blank and {@code current} is true.
     * One side missing → the other alone; both missing → null.
     */
    static String range(String start, String end, Boolean current) {
        String s = formatDate(start);
        String e = hasText(end) ? formatDate(end) : (Boolean.TRUE.equals(current) ? PRESENT : null);
        if (s != null && e != null) {
            return s + EN_DASH + e;
        }
        return s != null ? s : e;
    }

    private static void addEntryLine(List<Block> entry, String line, String detail) {
        if (line != null) {
            entry.add(new Block.Subheading(line, detail));
        } else if (detail != null) {
            entry.add(new Block.Paragraph(detail));
        }
    }

    private static void bullets(List<Block> entry, List<Bullet> bullets) {
        for (Bullet bullet : orEmpty(bullets)) {
            if (bullet != null && hasText(bullet.text())) {
                entry.add(new Block.BulletItem(bullet.text().strip()));
            }
        }
    }

    /** Heading + the non-empty entries, separated by a Spacer; nothing when every entry is empty. */
    private static void section(List<Block> blocks, String heading, List<List<Block>> entries) {
        List<List<Block>> nonEmpty = entries.stream().filter(e -> !e.isEmpty()).toList();
        if (nonEmpty.isEmpty()) {
            return;
        }
        blocks.add(new Block.Heading(heading));
        for (int i = 0; i < nonEmpty.size(); i++) {
            if (i > 0) {
                blocks.add(new Block.Spacer());
            }
            blocks.addAll(nonEmpty.get(i));
        }
    }

    static <T> List<T> orEmpty(List<T> list) {
        return list == null ? List.of() : list.stream().filter(java.util.Objects::nonNull).toList();
    }
}
