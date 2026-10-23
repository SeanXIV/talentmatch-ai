package com.talentmatch.application.tailoring;

import java.util.List;

/**
 * The job a document is tailored to, as read at generation time.
 *
 * @param description    the posting text (untrusted); may be null
 * @param requiredSkills the job's required skill names ({@code job_skill}), in stored order
 * @param niceSkills     the job's nice-to-have skill names
 */
public record JobFacts(String title, String company, String description, List<String> requiredSkills,
                       List<String> niceSkills) {

    public JobFacts {
        requiredSkills = requiredSkills == null ? List.of() : requiredSkills.stream().filter(s -> s != null).toList();
        niceSkills = niceSkills == null ? List.of() : niceSkills.stream().filter(s -> s != null).toList();
    }
}
