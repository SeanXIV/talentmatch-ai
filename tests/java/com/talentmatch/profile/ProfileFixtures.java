package com.talentmatch.profile;

import com.talentmatch.profile.ProfileDocument.Certification;
import com.talentmatch.profile.ProfileDocument.Education;
import com.talentmatch.profile.ProfileDocument.Experience;
import com.talentmatch.profile.ProfileDocument.Project;
import com.talentmatch.profile.ProfileDocument.SkillEntry;
import java.util.List;

/** Small builders for ProfileDocument in unit tests. */
final class ProfileFixtures {

    private ProfileFixtures() {
    }

    static ProfileDocument doc(String name, String email, List<Experience> exp, List<SkillEntry> skills) {
        return new ProfileDocument(name, email, null, null, null, null, List.of(), exp, List.of(), skills,
                List.of(), List.of(), List.of());
    }

    static ProfileDocument withSkills(SkillEntry... skills) {
        return doc("Ada Lovelace", null, List.of(), List.of(skills));
    }

    static ProfileDocument withExperience(Experience... exp) {
        return doc("Ada Lovelace", null, List.of(exp), List.of());
    }

    static ProfileDocument withProjects(Project... projects) {
        return new ProfileDocument("Ada Lovelace", null, null, null, null, null, List.of(), List.of(),
                List.of(projects), List.of(), List.of(), List.of(), List.of());
    }

    static ProfileDocument withCertifications(Certification... c) {
        return new ProfileDocument("Ada Lovelace", null, null, null, null, null, List.of(), List.of(), List.of(),
                List.of(), List.of(c), List.of(), List.of());
    }

    static ProfileDocument withEducation(Education... e) {
        return new ProfileDocument("Ada Lovelace", null, null, null, null, null, List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(e), List.of());
    }

    static Experience exp(String title, String company, String start, String end, Boolean current) {
        return new Experience(title, company, null, start, end, current, List.of(), List.of());
    }

    static Experience exp(String title, String company, String start, String end, Boolean current,
                          List<String> technologies, List<String> highlights) {
        return new Experience(title, company, null, start, end, current, technologies, highlights);
    }

    static SkillEntry skill(String name, Integer years) {
        return new SkillEntry(name, years);
    }
}
