package com.talentmatch.web.dto;

import java.util.List;

/**
 * Body of {@code POST /api/applications/{id}/generations} (optional: no body means both kinds, no notice
 * period). Unknown fields are rejected by the web layer (400 MALFORMED_REQUEST). {@code kinds} are strings
 * so an unknown value is a field error ("kinds must contain CV and/or COVER_LETTER, each at most once.").
 *
 * @param kinds               CV and/or COVER_LETTER (default both)
 * @param mentionNoticePeriod add the fixed notice-period sentence to the cover letter (default false)
 */
public record GenerationRequest(List<String> kinds, Boolean mentionNoticePeriod) {
}
