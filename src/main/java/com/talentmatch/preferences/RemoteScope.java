package com.talentmatch.preferences;

/** Which remote jobs the owner wants. */
public enum RemoteScope {
    /** Any remote job, wherever the employer hires. */
    ANYWHERE,
    /** Only remote jobs open to someone in one of the owner's countries (or worldwide). */
    ELIGIBLE_FROM_COUNTRIES
}
