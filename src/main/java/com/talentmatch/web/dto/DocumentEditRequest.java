package com.talentmatch.web.dto;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.UUID;

/**
 * Body of {@code POST /api/applications/{id}/documents}: an owner edit, stored as a new version. Unknown
 * fields are rejected by the web layer (400 MALFORMED_REQUEST). {@code content} is read as a tree because its
 * shape depends on the base version's kind; the service binds it (unknown fields and wrong types inside it
 * are 400 MALFORMED_REQUEST too).
 *
 * @param basedOn the version being edited (required; its kind and profile version carry over)
 * @param content the whole edited document in the shape {@code GET …/documents/{docId}} returns (required)
 */
public record DocumentEditRequest(UUID basedOn, JsonNode content) {
}
