package com.talentmatch.notify;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** §4.9 / §9.1 #13: exact layout, header-injection safety, caps, no CV data, zone rendering. */
class NotificationFormatterTest {

    private static final ZoneId JHB = ZoneId.of("Africa/Johannesburg");
    private static final UUID NID = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final UUID JOB = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
    private static final Instant NOW = Instant.parse("2026-10-11T10:00:00Z");

    private static NotificationContent content(String title, String company, Instant postedAt, List<String> flags,
                                               List<String> kinds, boolean adzuna, String url) {
        return new NotificationContent(JOB, title, company, "ONSITE", "Cape Town", List.of("ZA"), postedAt,
                Instant.parse("2026-10-11T09:05:00Z"), url, "4 of 4 required skills", List.of("Java", "SQL"),
                List.of(), flags, kinds, adzuna);
    }

    private static NotificationContent example() {
        return content("Backend Engineer", "Acme", Instant.parse("2026-10-11T08:00:00Z"), List.of(),
                List.of("Lever"), false, "https://jobs.lever.co/acme/n");
    }

    @Test
    void exactSubjectAndBodyForAFixedExample() {
        NotificationMessage m = NotificationFormatter.feed(NID, example(), 0.8, 0.6, JHB, "http://127.0.0.1:8080", NOW);
        assertThat(m.subject()).isEqualTo("[TalentMatch] 80% match: Backend Engineer at Acme");
        assertThat(m.textBody()).isEqualTo("""
                Backend Engineer
                Acme · On-site, Cape Town
                Score: 80% (4 of 4 required skills)
                Posted: 2 hours ago (2026-10-11 10:00 SAST); first seen 11:05 SAST
                Matched: Java, SQL
                Missing: none

                Apply: https://jobs.lever.co/acme/n
                Details: http://127.0.0.1:8080/api/feed/jobs/aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee
                Source: Lever

                --
                TalentMatch sent this because the job scored at least 60%. Change it with PUT /api/notifications/settings.
                """);
        assertThat(m.messageId()).isEqualTo("feed-" + NID);
    }

    @Test
    void nullPostedAtVariant() {
        NotificationContent c = content("Backend Engineer", "Acme", null, List.of(), List.of("Lever"), false,
                "https://x.example/j");
        String body = NotificationFormatter.feed(NID, c, 0.8, 0.6, JHB, "http://h", NOW).textBody();
        assertThat(body).contains("\nPosted: not stated by the source; first seen 11:05 SAST\n");
    }

    @Test
    void zoneRendering() {
        String body = NotificationFormatter.feed(NID, example(), 0.8, 0.6, ZoneId.of("UTC"), "http://h", NOW)
                .textBody();
        assertThat(body).contains("(2026-10-11 08:00 UTC); first seen 09:05 UTC");
    }

    @Test
    void notesLineOnlyWithFlagsAndInWords() {
        NotificationContent c = content("T", "C", NOW, List.of("REMOTE_ELIGIBILITY_UNKNOWN"), List.of("Lever"),
                false, "https://x.example/j");
        assertThat(NotificationFormatter.feed(NID, c, 0.8, 0.6, JHB, "http://h", NOW).textBody())
                .contains("\nNotes: remote eligibility unknown\n");
        assertThat(NotificationFormatter.feed(NID, example(), 0.8, 0.6, JHB, "http://h", NOW).textBody())
                .doesNotContain("Notes:");
    }

    @Test
    void adzunaAttributionOnlyWhenAttached() {
        NotificationContent with = content("T", "C", NOW, List.of(), List.of("Greenhouse", "Adzuna"), true,
                "https://x.example/j");
        assertThat(NotificationFormatter.feed(NID, with, 0.8, 0.6, JHB, "http://h", NOW).textBody())
                .contains("\nSource: Greenhouse, Adzuna; Jobs by Adzuna\n");
        assertThat(NotificationFormatter.feed(NID, example(), 0.8, 0.6, JHB, "http://h", NOW).textBody())
                .doesNotContain("Adzuna");
    }

    @Test
    void controlCharactersNeverReachTheSubject() {
        NotificationContent c = content("Engineer\r\nBcc: victim@example.com\u0000 ‮", "Acme\r\nX-Evil: 1",
                NOW, List.of(), List.of(), false, "https://x.example/j");
        NotificationMessage m = NotificationFormatter.feed(NID, c, 0.8, 0.6, JHB, "http://h", NOW);
        assertThat(m.subject()).doesNotContain("\r").doesNotContain("\n").doesNotContain("\u0000")
                .doesNotContain(" ").doesNotContain("‮");
        assertThat(m.subject()).isEqualTo("[TalentMatch] 80% match: Engineer Bcc: victim@example.com at Acme X-Evil: 1");
        assertThat(m.subject().codePoints().noneMatch(Character::isISOControl)).isTrue();
    }

    @Test
    void subjectCapCutsTheTitleFirst() {
        String longTitle = "Senior ".repeat(30) + "Engineer";
        NotificationContent c = content(longTitle, "Acme Corporation", NOW, List.of(), List.of(), false, null);
        String s = NotificationFormatter.feed(NID, c, 0.8, 0.6, JHB, "http://h", NOW).subject();
        assertThat(s.length()).isLessThanOrEqualTo(NotificationFormatter.MAX_SUBJECT);
        assertThat(s).startsWith("[TalentMatch] 80% match: Senior").endsWith("… at Acme Corporation");

        String longCompany = "Megacorp ".repeat(20);
        String s2 = NotificationFormatter.subject(longTitle, longCompany, 80);
        assertThat(s2.length()).isLessThanOrEqualTo(NotificationFormatter.MAX_SUBJECT);
    }

    @Test
    void perFieldCapsAndBodyCap() {
        String title = "T".repeat(500);
        String company = "C".repeat(500);
        NotificationContent c = new NotificationContent(JOB, title, company, "REMOTE", "L".repeat(500), List.of(),
                NOW, NOW, "https://x.example/j", "S".repeat(5000), java.util.Collections.nCopies(50, "K".repeat(200)),
                java.util.Collections.nCopies(50, "M".repeat(200)), java.util.stream.IntStream.range(0, 200).mapToObj(i -> "FLAG_" + i + "_" + "X".repeat(50)).toList(),
                java.util.Collections.nCopies(100, "Q".repeat(80)), false);
        String body = NotificationFormatter.feed(NID, c, 0.8, 0.6, JHB, "http://h", NOW).textBody();
        assertThat(body.length()).isLessThanOrEqualTo(NotificationFormatter.MAX_BODY);
        String firstLine = body.lines().findFirst().orElseThrow();
        assertThat(firstLine).hasSize(NotificationFormatter.MAX_TITLE).endsWith("…");
        assertThat(body.lines().skip(1).findFirst().orElseThrow()).startsWith("C".repeat(79) + "… · Remote, " + "L".repeat(119) + "…");
        assertThat(body).contains("(+42 more)");
    }

    @Test
    void unsafeUrlsAreDropped() {
        for (String bad : List.of("javascript:alert(1)", "ftp://x.example/a", "https://x.example/a b",
                "data:text/html,hi", "https://x\r\n.example")) {
            NotificationContent c = content("T", "C", NOW, List.of(), List.of(), false, bad);
            String body = NotificationFormatter.feed(NID, c, 0.8, 0.6, JHB, "http://h", NOW).textBody();
            assertThat(body).as(bad).doesNotContain("Apply:").doesNotContain("javascript");
        }
        assertThat(NotificationFormatter.url("HTTPS://x.example/a")).isEqualTo("HTTPS://x.example/a");
    }

    @Test
    void containsOnlyJobFacts() {
        // NotificationContent has no profile fields at all; the description is not part of it either.
        assertThat(NotificationContent.class.getRecordComponents()).extracting(c -> c.getName())
                .doesNotContain("description", "fullName", "email", "headline", "summaryText");
    }

    @Test
    void messageKeyIsStablePerNotification() {
        NotificationMessage a = NotificationFormatter.feed(NID, example(), 0.8, 0.6, JHB, "http://h", NOW);
        NotificationMessage b = NotificationFormatter.feed(NID, example(), 0.9, 0.5, JHB, "http://h",
                NOW.plus(Duration.ofHours(3)));
        assertThat(a.messageId()).isEqualTo(b.messageId()).isEqualTo("feed-" + NID);
    }

    @Test
    void testMessageHasNoJobData() {
        UUID id = UUID.randomUUID();
        NotificationMessage m = NotificationFormatter.test(id, false, 0.6, 20, JHB, NOW);
        assertThat(m.subject()).isEqualTo("[TalentMatch] Test notification");
        assertThat(m.messageId()).isEqualTo("test-" + id);
        assertThat(m.textBody()).contains("Minimum score: 60%").contains("Hourly cap: 20 emails")
                .contains("Notifications: disabled").contains("Sent: 2026-10-11 12:00 SAST")
                .doesNotContain("/api/feed/jobs/");
        assertThat(NotificationFormatter.test(id, true, 0.75, 5, JHB, NOW).textBody())
                .contains("Notifications: enabled\n").contains("Minimum score: 75%");
    }

    @Test
    void helpers() {
        assertThat(NotificationFormatter.workplaceWord("REMOTE")).isEqualTo("Remote");
        assertThat(NotificationFormatter.workplaceWord("HYBRID")).isEqualTo("Hybrid");
        assertThat(NotificationFormatter.workplaceWord("UNKNOWN")).isEqualTo("workplace not stated");
        assertThat(NotificationFormatter.workplaceWord(null)).isEqualTo("workplace not stated");
        assertThat(NotificationFormatter.relative(NOW.minusSeconds(10), NOW)).isEqualTo("just now");
        assertThat(NotificationFormatter.relative(NOW.minusSeconds(60), NOW)).isEqualTo("1 minute ago");
        assertThat(NotificationFormatter.relative(NOW.minus(Duration.ofHours(1)), NOW)).isEqualTo("1 hour ago");
        assertThat(NotificationFormatter.relative(NOW.minus(Duration.ofDays(3)), NOW)).isEqualTo("3 days ago");
        assertThat(NotificationFormatter.clean("  a \t\n b  ", 10)).isEqualTo("a b");
        assertThat(NotificationFormatter.clean(null, 10)).isEmpty();
        assertThat(NotificationFormatter.cap("abcdef", 4)).isEqualTo("abc…");
        // a surrogate pair is never split
        assertThat(NotificationFormatter.cap("ab😀cd", 4)).isEqualTo("ab…");
    }
}
