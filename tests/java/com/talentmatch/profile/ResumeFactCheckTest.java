package com.talentmatch.profile;

import static org.assertj.core.api.Assertions.assertThat;

import com.talentmatch.profile.ProfileDocument.Certification;
import com.talentmatch.profile.ProfileDocument.Education;
import com.talentmatch.profile.ProfileDocument.Experience;
import com.talentmatch.profile.ProfileDocument.Link;
import com.talentmatch.profile.ProfileDocument.Project;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Phase 4 (S10): numbers, date years, contacts, URLs and highlight wording are checked against the CV. */
class ResumeFactCheckTest {

    private static final String CV = """
            Ada Lovelace — ada.lovelace@example.com — +44 (20) 7946-0958
            linkedin.com/in/
            adalovelace
            Backend Engineer, Acme Ltd, 03/2019 - Present
            - Cut checkout latency by 40% for 10,000 daily users
            - Migrated billing services to Kubernetes
            Certified Kubernetes Administrator, issued 2021
            University of London, BSc Mathematics, 2012 - 2015
            """;

    private static List<String> paths(ProfileDocument d) {
        return ResumeFactCheck.check(d, CV).stream().map(ProfileWarning::path).toList();
    }

    private static ProfileDocument doc(String email, String phone, String summary, List<Link> links,
                                       List<Experience> exp, List<Project> projects, List<Certification> certs,
                                       List<Education> edu) {
        return new ProfileDocument("Ada Lovelace", email, phone, null, null, summary, links, exp, projects, List.of(),
                certs, edu, List.of());
    }

    private static ProfileDocument withHighlights(String... bullets) {
        return doc(null, null, null, List.of(), List.of(new Experience("Backend Engineer", "Acme Ltd", null, "2019-03",
                null, true, List.of(), List.of(bullets))), List.of(), List.of(), List.of());
    }

    @Test
    void faithfulDraftHasNoWarnings() {
        ProfileDocument d = doc("Ada.Lovelace@example.com", "+44 20 7946 0958", null,
                List.of(new Link("LinkedIn", "https://www.linkedin.com/in/adalovelace/")),
                List.of(new Experience("Backend Engineer", "Acme Ltd", null, "2019-03", null, true, List.of(),
                        List.of("Cut checkout latency by 40% for 10000 daily users",
                                "Migrated billing services to Kubernetes"))),
                List.of(), List.of(new Certification("Certified Kubernetes Administrator", null, "2021", null, null,
                        null)),
                List.of(new Education("University of London", "BSc", "Mathematics", "2012", "2015")));
        assertThat(ResumeFactCheck.check(d, CV)).isEmpty();
    }

    @Test
    void inventedNumbersInHighlightsSummaryAndDescriptionsAreWarned() {
        assertThat(paths(withHighlights("Cut checkout latency by 45% for 10,000 daily users")))
                .containsExactly("experience[0].highlights[0]");
        ProfileDocument d = doc(null, null, "Engineer with 12 years of experience", List.of(), List.of(),
                List.of(new Project("Atlas", "Served 3 million requests", List.of(), null, List.of())), List.of(),
                List.of());
        assertThat(paths(d)).containsExactly("summary", "projects[0].description");
        assertThat(ResumeFactCheck.check(d, CV).get(0).value()).isEqualTo("12");
    }

    @Test
    void dateYearsMustBeInTheCv() {
        ProfileDocument d = doc(null, null, null, List.of(),
                List.of(new Experience("Backend Engineer", "Acme Ltd", null, "2018-03", "2020", false, List.of(),
                        List.of())),
                List.of(), List.of(new Certification("CKA", null, "2021-05", "2024", null, null)),
                List.of(new Education("University of London", null, null, "2011", "2015")));
        assertThat(paths(d)).containsExactlyInAnyOrder("experience[0].startDate", "experience[0].endDate",
                "certifications[0].expires", "education[0].startDate");
    }

    @Test
    void contactDetailsMustBeInTheCv() {
        assertThat(paths(doc("ada@example.com", "+44 20 7946 0000", null, List.of(), List.of(), List.of(), List.of(),
                List.of()))).containsExactly("email", "phone");
        assertThat(paths(doc(null, "20-7946-0958", null, List.of(), List.of(), List.of(), List.of(), List.of())))
                .as("digits only; a sub-sequence of the CV's digits").isEmpty();
    }

    @Test
    void urlsAreComparedWithoutSchemeWwwSlashAndLineBreaks() {
        assertThat(paths(doc(null, null, null, List.of(new Link("LinkedIn", "linkedin.com/in/adalovelace")),
                List.of(), List.of(), List.of(), List.of()))).isEmpty();
        assertThat(paths(doc(null, null, null, List.of(new Link("GitHub", "https://github.com/ada")), List.of(),
                List.of(new Project("Atlas", null, List.of(), "https://atlas.dev", List.of())),
                List.of(new Certification("CKA", null, null, null, null, "https://cncf.io/verify/1")), List.of())))
                .containsExactly("links[0].url", "projects[0].url", "certifications[0].url");
    }

    @Test
    void rewordedHighlightsAreWarned() {
        assertThat(paths(withHighlights("Spearheaded transformative cloud-native modernization initiatives")))
                .containsExactly("experience[0].highlights[0]");
        assertThat(paths(withHighlights("Migrated the billing services to Kubernetes clusters")))
                .as("5 of 6 content words found (83%)").isEmpty();
        assertThat(ResumeFactCheck.check(withHighlights("Migrated billing services to 4 Kubernetes"), CV))
                .as("number 4 not in CV, wording fine").singleElement()
                .satisfies(w -> assertThat(w.message()).contains("number 4"));
        assertThat(paths(withHighlights("Did it"))).as("no content words: nothing to compare").isEmpty();
    }

    @Test
    void ligaturesInTheCvDoNotCauseFalseWarnings() {
        ProfileDocument d = withHighlights("Certified office workflow automation");
        assertThat(ResumeFactCheck.check(d, "Since 2019: Certiﬁed oﬃce workﬂow automation")).isEmpty();
    }

    @Test
    void groundingIncludesFactCheckWarnings() {
        assertThat(ResumeGrounding.check(withHighlights("Cut latency by 99%"), CV))
                .extracting(ProfileWarning::path).contains("experience[0].highlights[0]");
    }
}
