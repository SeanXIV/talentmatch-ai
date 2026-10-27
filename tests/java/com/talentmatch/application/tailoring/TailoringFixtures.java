package com.talentmatch.application.tailoring;

import com.talentmatch.feed.skills.DictionarySkillMatcher;
import com.talentmatch.feed.skills.SkillDictionary;
import com.talentmatch.profile.ProfileDocument;
import com.talentmatch.profile.ProfileDocument.Certification;
import com.talentmatch.profile.ProfileDocument.Education;
import com.talentmatch.profile.ProfileDocument.Experience;
import com.talentmatch.profile.ProfileDocument.Language;
import com.talentmatch.profile.ProfileDocument.Link;
import com.talentmatch.profile.ProfileDocument.Project;
import com.talentmatch.profile.ProfileDocument.SkillEntry;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Shared fixtures for the Phase 7 grounding tests: a hand-built vocabulary snapshot and one fixed profile.
 *
 * <p>The profile (today = 2026-10-19):
 * <ul>
 *   <li>E1 Senior Backend Engineer, Acme Ltd, 2021-03..present (5 years), tech Java 17, PostgreSQL, Kafka:
 *       H1 "Cut API latency by 40% for 12,000 daily users", H2 "Led a team of five engineers",
 *       H3 "Migrated billing to Kafka event streams".</li>
 *   <li>E2 Backend Engineer, Globex, 2016-01..2020-12 (4 years), tech Python, Docker:
 *       H1 "Built Python ETL pipelines processing 2 million rows nightly",
 *       H2 "Containerised services with Docker in 2018".</li>
 *   <li>Career span 2016-01..2026-10 = 10 years. Neither 10 nor 4 is written anywhere in the profile.</li>
 *   <li>P1 Ledger (Go), P2 Site (HTML5); C1 AWS Certified Developer (2022-05..2025-05, credential ABC-123);
 *       D1 University of Stellenbosch 2012..2015; skills Java 7, PostgreSQL 5, Kafka, Python 3, Docker, Go,
 *       HTML5.</li>
 * </ul>
 */
public final class TailoringFixtures {

    public static final LocalDate TODAY = LocalDate.of(2026, 10, 19);

    public static final String NAME = "Zoë Ndlovu-Müller";
    public static final String EMAIL = "zoe@example.com";
    public static final String PHONE = "+27 82 555 0199";
    public static final String LOCATION = "Cape Town";
    public static final String GITHUB = "https://github.com/zoe-nm";
    public static final String CREDENTIAL_ID = "ABC-123";
    public static final String CERT_URL = "https://verify.example.org/cert/abc";

    public static final String E1H1 = "Cut API latency by 40% for 12,000 daily users";
    public static final String E1H2 = "Led a team of five engineers";
    public static final String E1H3 = "Migrated billing to Kafka event streams";
    public static final String E2H1 = "Built Python ETL pipelines processing 2 million rows nightly";
    public static final String E2H2 = "Containerised services with Docker in 2018";
    public static final String P1H1 = "Wrote the core ledger engine with 95% test coverage";
    public static final String P2H1 = "Hand-written HTML5 and CSS";

    /** Skill name → aliases (lower-cased, as stored). */
    private static final Map<String, List<String>> VOCABULARY = new LinkedHashMap<>();

    static {
        VOCABULARY.put("Java", List.of());
        VOCABULARY.put("PostgreSQL", List.of("postgres"));
        VOCABULARY.put("Kafka", List.of());
        VOCABULARY.put("Python", List.of());
        VOCABULARY.put("Docker", List.of());
        VOCABULARY.put("Kubernetes", List.of("k8s"));
        VOCABULARY.put("Go", List.of("golang"));
        VOCABULARY.put("HTML5", List.of());
        VOCABULARY.put("Spring Boot", List.of());
        VOCABULARY.put("Spring", List.of());
        VOCABULARY.put("React", List.of());
        VOCABULARY.put("Terraform", List.of());
    }

    private TailoringFixtures() {
    }

    /** A stable id per skill name. */
    public static UUID id(String skill) {
        return UUID.nameUUIDFromBytes(("skill:" + skill).getBytes(StandardCharsets.UTF_8));
    }

    /** The hand-built vocabulary snapshot (byKey holds lower-cased names and aliases). */
    public static SkillDictionary.Snapshot snapshot() {
        Map<UUID, String> names = new HashMap<>();
        Map<String, UUID> byKey = new HashMap<>();
        List<DictionarySkillMatcher.Term> terms = new ArrayList<>();
        VOCABULARY.forEach((name, aliases) -> {
            UUID id = id(name);
            names.put(id, name);
            terms.add(new DictionarySkillMatcher.Term(id, name, name));
            for (String alias : aliases) {
                terms.add(new DictionarySkillMatcher.Term(id, name, alias));
                byKey.put(alias, id);
            }
        });
        names.forEach((id, name) -> byKey.put(name.toLowerCase(Locale.ROOT), id));
        return new SkillDictionary.Snapshot("fp", Map.copyOf(names), Map.copyOf(byKey),
                new DictionarySkillMatcher(terms, DictionarySkillMatcher.DEFAULT_AMBIGUOUS_NAMES));
    }

    public static FactCheck check() {
        return new FactCheck(snapshot());
    }

    public static Experience e1() {
        return new Experience("Senior Backend Engineer", "Acme Ltd", "Cape Town", "2021-03", null, true,
                List.of("Java 17", "PostgreSQL", "Kafka"), List.of(E1H1, E1H2, E1H3));
    }

    public static Experience e2() {
        return new Experience("Backend Engineer", "Globex", "Johannesburg", "2016-01", "2020-12", false,
                List.of("Python", "Docker"), List.of(E2H1, E2H2));
    }

    public static ProfileDocument profile() {
        return new ProfileDocument(NAME, EMAIL, PHONE, LOCATION,
                "Backend engineer",
                "Backend engineer with 7 years of Java and PostgreSQL.",
                List.of(new Link("GitHub", GITHUB)),
                List.of(e1(), e2()),
                List.of(new Project("Ledger", "Open-source double-entry ledger", List.of("Go"),
                                "https://ledger.example.org", List.of(P1H1)),
                        new Project("Site", "Personal website", List.of("HTML5"), null, List.of(P2H1))),
                List.of(new SkillEntry("Java", 7), new SkillEntry("PostgreSQL", 5), new SkillEntry("Kafka", null),
                        new SkillEntry("Python", 3), new SkillEntry("Docker", null), new SkillEntry("Go", null),
                        new SkillEntry("HTML5", null)),
                List.of(new Certification("AWS Certified Developer", "Amazon", "2022-05", "2025-05", CREDENTIAL_ID,
                        CERT_URL)),
                List.of(new Education("University of Stellenbosch", "BSc", "Computer Science", "2012", "2015")),
                List.of(new Language("English", "Native"), new Language("isiXhosa", "Conversational")));
    }

    public static ProfileFacts facts() {
        return ProfileFacts.of(profile(), 12000, check(), TODAY);
    }

    public static ProfileFacts facts(ProfileDocument profile) {
        return ProfileFacts.of(profile, 12000, check(), TODAY);
    }

    public static JobFacts job() {
        return new JobFacts("Senior Java Developer", "Initech",
                "We build payments with Java, Kubernetes and Docker. Ask Peter Gibbons about the team.",
                List.of("Java", "Kubernetes"), List.of("Docker"));
    }

    /** A profile with only the given roles (no projects, skills, certifications or education). */
    public static ProfileDocument withRoles(Experience... roles) {
        return new ProfileDocument(NAME, null, null, null, null, null, List.of(), List.of(roles), List.of(),
                List.of(), List.of(), List.of(), List.of());
    }
}
