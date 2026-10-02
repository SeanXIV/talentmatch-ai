package com.talentmatch.service;

/** Why a match page has no ranked candidates. */
public enum MatchReason {
    /** The job lists no skills, so nobody can be scored. */
    JOB_HAS_NO_SKILLS("This job has no skills listed yet, so candidates can't be ranked. Add skills to see matches."),
    /** The job is matchable but there are no candidates at all. */
    NO_CANDIDATES("There are no candidates yet. Add candidates to see matches.");

    private final String message;

    MatchReason(String message) {
        this.message = message;
    }

    public String message() {
        return message;
    }
}
