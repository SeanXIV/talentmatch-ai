package com.talentmatch.application.content;

import java.util.List;

/**
 * A tailored CV version (stored as {@code application_document.content}). Self-contained: the facts
 * are copied from the profile version; only {@code headline}, {@code summary}, the selection and order
 * of skills, highlights, projects and certifications, and the highlight texts are tailored.
 */
public record TailoredCvContent(
        Header header,
        String headline,
        String summary,
        List<String> skills,
        List<ExperienceItem> experience,
        List<ProjectItem> projects,
        List<CertificationItem> certifications,
        List<EducationItem> education,
        List<LanguageItem> languages) {

    public TailoredCvContent {
        skills = Lists.of(skills);
        experience = Lists.of(experience);
        projects = Lists.of(projects);
        certifications = Lists.of(certifications);
        education = Lists.of(education);
        languages = Lists.of(languages);
    }
}
