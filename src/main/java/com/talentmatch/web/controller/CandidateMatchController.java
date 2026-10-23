package com.talentmatch.web.controller;

import com.talentmatch.config.MatchProperties;
import com.talentmatch.seeker.CandidateMatchPageView;
import com.talentmatch.seeker.CandidateMatchService;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Ranked, explained jobs for a candidate (Phase 6), and the owner's shortcut to the same list
 * (decision a). Parameter rules live in {@link CandidateMatchParams}; the parameters are validated
 * before the service runs, so a 400 never uses up a regenerate slot.
 */
@RestController
@RequestMapping("/api")
public class CandidateMatchController {

    private final CandidateMatchService service;
    private final MatchProperties matchProperties;
    private final Clock clock;

    public CandidateMatchController(CandidateMatchService service, MatchProperties matchProperties, Clock clock) {
        this.service = service;
        this.matchProperties = matchProperties;
        this.clock = clock;
    }

    @GetMapping("/candidates/{id}/matches")
    public CandidateMatchPageView candidateMatches(
            @PathVariable("id") UUID id,
            @RequestParam(name = "limit", required = false) Integer limit,
            @RequestParam(name = "page", required = false) Integer page,
            @RequestParam(name = "minScore", required = false) Double minScore,
            @RequestParam(name = "sort", required = false) String sort,
            @RequestParam(name = "postedSince", required = false) String postedSince,
            @RequestParam(name = "country", required = false) List<String> country,
            @RequestParam(name = "remote", required = false) String remote,
            @RequestParam(name = "location", required = false) String location,
            @RequestParam(name = "origin", required = false) String origin,
            @RequestParam(name = "includeClosed", defaultValue = "false") boolean includeClosed,
            @RequestParam(name = "includeFiltered", defaultValue = "false") boolean includeFiltered,
            @RequestParam(name = "regenerate", defaultValue = "false") boolean regenerate) {
        CandidateMatchParams.Parsed parsed = parse(limit, page, minScore, sort, postedSince, country, remote,
                location, origin, includeClosed, includeFiltered);
        return service.getMatches(id, parsed.query(), parsed.page(), parsed.limit(), regenerate);
    }

    /**
     * {@code /candidates/{ownerCandidateId}/matches} with the same parameters (decision a);
     * 404 PROFILE_NOT_FOUND without a profile.
     */
    @GetMapping("/profile/matches")
    public CandidateMatchPageView profileMatches(
            @RequestParam(name = "limit", required = false) Integer limit,
            @RequestParam(name = "page", required = false) Integer page,
            @RequestParam(name = "minScore", required = false) Double minScore,
            @RequestParam(name = "sort", required = false) String sort,
            @RequestParam(name = "postedSince", required = false) String postedSince,
            @RequestParam(name = "country", required = false) List<String> country,
            @RequestParam(name = "remote", required = false) String remote,
            @RequestParam(name = "location", required = false) String location,
            @RequestParam(name = "origin", required = false) String origin,
            @RequestParam(name = "includeClosed", defaultValue = "false") boolean includeClosed,
            @RequestParam(name = "includeFiltered", defaultValue = "false") boolean includeFiltered,
            @RequestParam(name = "regenerate", defaultValue = "false") boolean regenerate) {
        CandidateMatchParams.Parsed parsed = parse(limit, page, minScore, sort, postedSince, country, remote,
                location, origin, includeClosed, includeFiltered);
        return service.getOwnerMatches(parsed.query(), parsed.page(), parsed.limit(), regenerate);
    }

    private CandidateMatchParams.Parsed parse(Integer limit, Integer page, Double minScore, String sort,
                                              String postedSince, List<String> country, String remote,
                                              String location, String origin, boolean includeClosed,
                                              boolean includeFiltered) {
        return CandidateMatchParams.parse(
                new CandidateMatchParams.Raw(limit, page, minScore, sort, postedSince, country, remote, location,
                        origin, includeClosed, includeFiltered),
                matchProperties.defaultLimit(), matchProperties.maxLimit(), clock);
    }
}
