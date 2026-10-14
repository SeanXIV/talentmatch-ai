package com.talentmatch.web.dto;

/**
 * Body of {@code POST /api/feed/sources/lookup}. Unknown fields are rejected by the web layer
 * (400 MALFORMED_REQUEST).
 *
 * @param companyName the company's name as on its careers page, e.g. "Acme Payments" (required, at most
 *                    200 characters)
 */
public record CompanyLookupRequest(String companyName) {
}
