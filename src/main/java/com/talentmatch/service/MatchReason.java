package com.talentmatch.service;

/** Why a match page has no (or only zero-score) ranked candidates or jobs. */
public enum MatchReason {
    /** The job lists no skills, so nobody can be scored. */
    JOB_HAS_NO_SKILLS("This job has no skills listed yet, so candidates can't be ranked. Add skills to see matches."),
    /** The job is matchable but there are no candidates at all. */
    NO_CANDIDATES("There are no candidates yet. Add candidates to see matches."),
    /** Candidate side: no in-scope job lists a skill. */
    NO_MATCHABLE_JOBS("No job with listed skills matches these filters. Jobs without skills can't be ranked; "
            + "widen the filters or wait for the feed to find skills."),
    /** Candidate side: the candidate has no skills, so every job scores 0. */
    CANDIDATE_HAS_NO_SKILLS("This candidate has no skills yet, so every job scores 0%. Add skills (or confirm "
            + "your profile with PUT /api/profile) to see real matches.");

    private final String message;

    MatchReason(String message) {
        this.message = message;
    }

    public String message() {
        return message;
    }
}
