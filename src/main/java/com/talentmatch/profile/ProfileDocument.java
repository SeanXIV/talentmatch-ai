package com.talentmatch.profile;

import dev.langchain4j.model.output.structured.Description;
import java.util.List;

/**
 * A structured CV: the AI-extracted draft, the owner's edits and the confirmed master profile
 * all use this shape. The LLM output schema mirrors it in {@link ProfileJsonSchema} (keep both in
 * step; the descriptions here document the fields). Dates are
 * {@code YYYY-MM} or {@code YYYY}; an empty {@code endDate} with {@code current = true} means
 * "present".
 */
public record ProfileDocument(
        @Description("Full name exactly as written in the CV") String fullName,
        @Description("Email address from the CV, or null") String email,
        @Description("Phone number from the CV, or null") String phone,
        @Description("City/country from the CV, or null") String location,
        @Description("The CV's own one-line title or headline, or null") String headline,
        @Description("The CV's profile/summary paragraph, copied faithfully, or null") String summary,
        @Description("Links listed in the CV (LinkedIn, GitHub, portfolio)") List<Link> links,
        @Description("Every job/role in the CV, most recent first") List<Experience> experience,
        @Description("Every project in the CV") List<Project> projects,
        @Description("Every skill, tool, language or technology named in the CV") List<SkillEntry> skills,
        @Description("Every certification in the CV") List<Certification> certifications,
        @Description("Every education entry in the CV") List<Education> education,
        @Description("Spoken languages listed in the CV") List<Language> languages) {

    public record Link(
            @Description("e.g. LinkedIn, GitHub, Portfolio") String label,
            @Description("The URL exactly as written") String url) {
    }

    public record Experience(
            @Description("Job title as written") String title,
            @Description("Employer name as written") String company,
            @Description("Location, or null") String location,
            @Description("Start date as YYYY-MM or YYYY, or null") String startDate,
            @Description("End date as YYYY-MM or YYYY; null if current or not stated") String endDate,
            @Description("true only if the CV says this role is current (e.g. 'Present')") Boolean current,
            @Description("Technologies/tools the CV names for this role") List<String> technologies,
            @Description("Responsibilities and achievements, one bullet each, faithfully copied") List<String> highlights) {
    }

    public record Project(
            @Description("Project name as written") String name,
            @Description("Short description from the CV, or null") String description,
            @Description("Technologies the CV names for this project") List<String> technologies,
            @Description("Project URL, or null") String url,
            @Description("Achievements or details, one bullet each") List<String> highlights) {
    }

    public record SkillEntry(
            @Description("Skill name as written, e.g. Java, PostgreSQL, Docker") String name,
            @Description("Years of experience ONLY if the CV states a number for this skill, else null") Integer years) {
    }

    public record Certification(
            @Description("Certification name as written") String name,
            @Description("Issuing organisation, or null") String issuer,
            @Description("Issue date as YYYY-MM or YYYY, or null") String issued,
            @Description("Expiry date as YYYY-MM or YYYY, or null") String expires,
            @Description("Credential ID, or null") String credentialId,
            @Description("Verification URL, or null") String url) {
    }

    public record Education(
            @Description("School, college or university as written") String institution,
            @Description("Degree/diploma/certificate name, or null") String qualification,
            @Description("Field of study, or null") String field,
            @Description("Start date as YYYY-MM or YYYY, or null") String startDate,
            @Description("End/graduation date as YYYY-MM or YYYY, or null") String endDate) {
    }

    public record Language(
            @Description("Language name") String name,
            @Description("Proficiency as written, or null") String level) {
    }
}
