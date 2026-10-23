package com.talentmatch.application.content;

/**
 * Grounding finding codes (§4.4). Stored by name in {@code application_document.findings}; never rename.
 *
 * <ul>
 *   <li>INFO (repairs): {@link #UNKNOWN_REF}, {@link #DUPLICATE_REF}, {@link #SKILL_NOT_IN_PROFILE} (CV skills
 *       list), {@link #ROLE_DEFAULTED}, {@link #SKILLS_DEFAULTED}, {@link #REVERTED}, {@link #PROFILE_TRUNCATED},
 *       {@link #NOTICE_PERIOD_UNKNOWN}.</li>
 *   <li>WARNING (heuristics): {@link #HEAVILY_REWORDED}, {@link #UNKNOWN_NAME}, {@link #LENGTH}.</li>
 *   <li>BLOCKING in AI cover letters, WARNING in EDIT versions: {@link #NUMBER_NOT_IN_PROFILE},
 *       {@link #SKILL_NOT_IN_PROFILE} (free text), {@link #YEAR_NOT_IN_PROFILE}, {@link #CONTACT_DETAILS}.</li>
 *   <li>BLOCKING, computed at read time: {@link #PROFILE_CHANGED}.</li>
 * </ul>
 */
public enum FindingCode {
    UNKNOWN_REF,
    DUPLICATE_REF,
    SKILL_NOT_IN_PROFILE,
    ROLE_DEFAULTED,
    SKILLS_DEFAULTED,
    REVERTED,
    PROFILE_TRUNCATED,
    NOTICE_PERIOD_UNKNOWN,
    HEAVILY_REWORDED,
    UNKNOWN_NAME,
    LENGTH,
    NUMBER_NOT_IN_PROFILE,
    YEAR_NOT_IN_PROFILE,
    CONTACT_DETAILS,
    PROFILE_CHANGED
}
