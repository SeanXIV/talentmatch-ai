package com.talentmatch.application.render;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.talentmatch.application.content.Bullet;
import com.talentmatch.application.content.CertificationItem;
import com.talentmatch.application.content.CoverLetterContent;
import com.talentmatch.application.content.EducationItem;
import com.talentmatch.application.content.ExperienceItem;
import com.talentmatch.application.content.Header;
import com.talentmatch.application.content.LanguageItem;
import com.talentmatch.application.content.LinkItem;
import com.talentmatch.application.content.ProjectItem;
import com.talentmatch.application.content.TailoredCvContent;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Fixed contents for the layout and writer tests, and system TTFs for {@link PdfWriter}.
 *
 * <p>The bundled Noto Sans files ({@code src/main/resources/fonts}) are not in the repo yet, so the PDF tests
 * use the package-private {@code PdfWriter(Supplier, Supplier)} constructor with the first system font pair
 * found (DejaVu Sans, Ubuntu, Liberation Sans, or Arial via /mnt/c). They are skipped (not failed) when none
 * exists. The tests that need the real Noto files are guarded by {@link #assumeBundledFonts()}.
 */
final class RenderFixtures {

    static final String NAME = "Zoë Ndlovu-Müller";
    static final Instant CREATED = Instant.parse("2026-10-19T08:15:30Z");

    private static final List<String[]> FONT_PAIRS = List.of(
            new String[] {"/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf",
                    "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf"},
            new String[] {"/usr/share/fonts/dejavu/DejaVuSans.ttf", "/usr/share/fonts/dejavu/DejaVuSans-Bold.ttf"},
            new String[] {"/usr/share/fonts/truetype/ubuntu/Ubuntu-R.ttf", "/usr/share/fonts/truetype/ubuntu/Ubuntu-B.ttf"},
            new String[] {"/usr/share/fonts/truetype/liberation/LiberationSans-Regular.ttf",
                    "/usr/share/fonts/truetype/liberation/LiberationSans-Bold.ttf"},
            new String[] {"/mnt/c/Windows/Fonts/arial.ttf", "/mnt/c/Windows/Fonts/arialbd.ttf"},
            new String[] {"C:/Windows/Fonts/arial.ttf", "C:/Windows/Fonts/arialbd.ttf"});

    private static volatile byte[][] systemFonts;

    private RenderFixtures() {
    }

    /** A PdfWriter over a system font pair; skips the test when there is none. */
    static PdfWriter pdfWriter() {
        byte[][] fonts = systemFonts();
        assumeTrue(fonts != null, "no system TTF found for the PDF tests");
        return new PdfWriter(() -> fonts[0], () -> fonts[1]);
    }

    /** The regular/bold system font pair used, or null. */
    static byte[][] systemFonts() {
        if (systemFonts == null) {
            for (String[] pair : FONT_PAIRS) {
                Path regular = Path.of(pair[0]);
                Path bold = Path.of(pair[1]);
                if (Files.isReadable(regular) && Files.isReadable(bold)) {
                    try {
                        systemFonts = new byte[][] {Files.readAllBytes(regular), Files.readAllBytes(bold)};
                        break;
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                }
            }
        }
        return systemFonts;
    }

    /** Skips the test until {@code fonts/NotoSans-Regular.ttf} and {@code -Bold.ttf} are on the classpath. */
    static void assumeBundledFonts() {
        ClassLoader loader = RenderFixtures.class.getClassLoader();
        assumeTrue(loader.getResource(PdfFonts.REGULAR) != null && loader.getResource(PdfFonts.BOLD) != null,
                "bundled Noto Sans fonts are not in src/main/resources/fonts yet");
    }

    static Header header() {
        return new Header(NAME, "zoe@example.com", "+27 82 555 0199", "Cape Town",
                List.of(new LinkItem("GitHub", "https://github.com/zoe-nm")));
    }

    static TailoredCvContent cv() {
        return new TailoredCvContent(header(), "Backend engineer", "Summary text.", List.of("Java", "PostgreSQL"),
                List.of(new ExperienceItem("E1", "Senior Backend Engineer", "Acme Ltd", "Cape Town", "2021-03", null,
                                true, List.of("Java"), List.of(new Bullet("E1.H1", "Cut latency by 40%"))),
                        new ExperienceItem("E2", "Backend Engineer", "Globex", "Johannesburg", "2016-01", "2020-12",
                                false, List.of(), List.of(new Bullet("E2.H1", "Built ETL pipelines")))),
                List.of(new ProjectItem("P1", "Ledger", "Double-entry ledger", List.of("Go", "PostgreSQL"),
                        "https://ledger.example.org", List.of(new Bullet("P1.H1", "Wrote the core")))),
                List.of(new CertificationItem("C1", "AWS Certified Developer", "Amazon", "2022-05", "2025-05")),
                List.of(new EducationItem("D1", "University of Stellenbosch", "BSc", "Computer Science", "2012", "2015")),
                List.of(new LanguageItem("English", "Native"), new LanguageItem("isiXhosa", null)));
    }

    /** A CV long enough for several PDF pages: 12 roles of 6 long bullets. */
    static TailoredCvContent longCv() {
        List<ExperienceItem> roles = new ArrayList<>();
        for (int i = 1; i <= 12; i++) {
            List<Bullet> bullets = new ArrayList<>();
            for (int j = 1; j <= 6; j++) {
                bullets.add(new Bullet("E" + i + ".H" + j, "Point " + j + " of role " + i + ": "
                        + "designed, built and operated reliable backend services with careful testing. ".repeat(3)));
            }
            roles.add(new ExperienceItem("E" + i, "Engineer " + i, "Company " + i, null, "2010", "2011", false,
                    List.of(), bullets));
        }
        return new TailoredCvContent(header(), null, null, List.of(), roles, List.of(), List.of(), List.of(),
                List.of());
    }

    static CoverLetterContent letter() {
        return new CoverLetterContent(header(), "Initech", "Senior Java Developer", LocalDate.of(2026, 10, 19),
                "Dear Hiring Manager,", List.of("First paragraph.", "Second paragraph."), "Kind regards,", NAME);
    }
}
