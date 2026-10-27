package com.talentmatch.application.content;

/**
 * One education entry of a tailored CV, copied from the profile version (always all of them, in order).
 *
 * @param ref {@code D1..Dn} as numbered in the profile
 */
public record EducationItem(String ref, String institution, String qualification, String field, String startDate,
                            String endDate) {
}
