package com.talentmatch.web.dto;

import com.talentmatch.feed.CompanyLookupService;
import com.talentmatch.feed.source.SourceKind;
import java.util.List;
import java.util.UUID;

/**
 * {@code 200} for {@code POST /api/feed/sources/lookup} (step 12). Nothing is added: a found board is
 * added with {@code POST /api/feed/sources} using its {@code kind} and {@code boardToken}.
 *
 * @param companyName the name looked up (normalized)
 * @param candidates  the board names tried, most specific first (guesses)
 * @param found       boards that exist; {@code sourceId} is set when one is already on the watchlist
 * @param unchecked   boards that couldn't be checked, with the reason
 * @param complete    false when the time budget ran out before every board was checked
 */
public record CompanyLookupResponse(String companyName, List<String> candidates, List<Found> found,
                                    List<Unchecked> unchecked, boolean complete) {

    /**
     * @param companyName  the provider's name for the board (Greenhouse), or the watched source's name
     * @param openPostings the open postings when known (Ashby, empty boards, watched sources); else null
     * @param warning      e.g. no open postings, or already on the watchlist; else null
     * @param sourceId     the existing source when already watched; else null
     */
    public record Found(SourceKind kind, String boardToken, String companyName, Integer openPostings,
                        String warning, UUID sourceId) {
    }

    /**
     * @param reason TIMEOUT, NETWORK, SERVER_ERROR, RATE_LIMITED, UNAUTHORIZED, INVALID_RESPONSE, TOO_LARGE,
     *               BUSY or BUDGET_EXCEEDED
     */
    public record Unchecked(SourceKind kind, String boardToken, String reason) {
    }

    public static CompanyLookupResponse of(CompanyLookupService.LookupResult result) {
        return new CompanyLookupResponse(result.companyName(), result.candidates(),
                result.found().stream().map(f -> new Found(f.kind(), f.boardToken(), f.companyName(),
                        f.openPostings(), f.warning(), f.sourceId())).toList(),
                result.unchecked().stream().map(u -> new Unchecked(u.kind(), u.boardToken(), u.reason())).toList(),
                result.complete());
    }
}
