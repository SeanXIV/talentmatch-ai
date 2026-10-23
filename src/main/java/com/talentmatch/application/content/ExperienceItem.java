package com.talentmatch.application.content;

import java.util.List;

/**
 * One role of a tailored CV. Every field except {@code highlights} is copied from the profile version
 * (read-only in edits).
 *
 * @param ref {@code E1..En} in profile order
 */
public record ExperienceItem(String ref, String title, String company, String location, String startDate,
                             String endDate, Boolean current, List<String> technologies, List<Bullet> highlights) {

    public ExperienceItem {
        technologies = Lists.of(technologies);
        highlights = Lists.of(highlights);
    }
}
