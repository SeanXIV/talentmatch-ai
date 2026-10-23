package com.talentmatch.web.dto;

/**
 * Body of {@code POST /api/applications/{id}/documents/{docId}/approve} (optional: no body means
 * {@code acknowledgeFindings=false}). Unknown fields are rejected by the web layer (400 MALFORMED_REQUEST).
 *
 * @param acknowledgeFindings the owner has read the version's BLOCKING findings (required when it has any)
 */
public record ApproveRequest(Boolean acknowledgeFindings) {
}
