package com.talentmatch.preferences;

import java.util.List;

/** Seniority of a role. Stored in feed_job.seniority (V5). UNKNOWN is never a preference value. */
public enum Seniority {
    INTERN,
    JUNIOR,
    MID,
    SENIOR,
    LEAD,
    PRINCIPAL,
    MANAGER,
    UNKNOWN;

    /**
     * Seniority from a job title, by token (so "International" is not "intern"). The first rule that
     * matches wins, in this order: intern, junior, senior, lead, principal, manager, mid
     * ("Senior Engineering Manager" → SENIOR).
     */
    public static Seniority fromTitle(String title) {
        List<String> t = TitleMatcher.tokens(title, false);
        if (t.isEmpty()) {
            return UNKNOWN;
        }
        if (t.contains("intern") || t.contains("internship")) {
            return INTERN;
        }
        if (t.contains("junior") || t.contains("jr") || t.contains("graduate") || t.contains("entry")) {
            return JUNIOR;
        }
        if (t.contains("senior") || t.contains("sr")) {
            return SENIOR;
        }
        if (t.contains("lead") || t.contains("staff")) {
            return LEAD;
        }
        if (t.contains("principal") || t.contains("architect")) {
            return PRINCIPAL;
        }
        if (t.contains("manager") || t.contains("director") || TitleMatcher.containsSequence(t, List.of("head", "of"))) {
            return MANAGER;
        }
        if (t.contains("intermediate") || t.contains("mid")) {
            return MID;
        }
        return UNKNOWN;
    }
}
