package com.talentmatch.application.content;

import java.util.List;

/**
 * One project of a tailored CV. Every field except {@code highlights} is copied from the profile version.
 *
 * @param ref {@code P1..Pn} as numbered in the profile
 */
public record ProjectItem(String ref, String name, String description, List<String> technologies, String url,
                          List<Bullet> highlights) {

    public ProjectItem {
        technologies = Lists.of(technologies);
        highlights = Lists.of(highlights);
    }
}
