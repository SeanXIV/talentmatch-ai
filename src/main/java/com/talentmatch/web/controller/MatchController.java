package com.talentmatch.web.controller;

import com.talentmatch.config.MatchProperties;
import com.talentmatch.service.MatchPageView;
import com.talentmatch.service.MatchService;
import com.talentmatch.service.RecomputeRunView;
import com.talentmatch.service.RecomputeService;
import com.talentmatch.service.exception.InvalidParameterException;
import com.talentmatch.service.exception.NotFoundException;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Ranked matches per job, plus the batch recompute resource. */
@RestController
@RequestMapping("/api")
@Validated
public class MatchController {

    private final MatchService matchService;
    private final RecomputeService recomputeService;
    private final MatchProperties matchProperties;

    public MatchController(MatchService matchService, RecomputeService recomputeService,
                           MatchProperties matchProperties) {
        this.matchService = matchService;
        this.recomputeService = recomputeService;
        this.matchProperties = matchProperties;
    }

    @GetMapping("/jobs/{id}/matches")
    public MatchPageView matches(
            @PathVariable("id") UUID id,
            @RequestParam(name = "limit", required = false) Integer limit,
            @RequestParam(name = "page", defaultValue = "0")
            @Min(value = 0, message = ApiParams.PAGE_MESSAGE) int page,
            @RequestParam(name = "minScore", defaultValue = "0")
            @DecimalMin(value = "0.0", message = ApiParams.MIN_SCORE_MESSAGE)
            @DecimalMax(value = "1.0", message = ApiParams.MIN_SCORE_MESSAGE) double minScore,
            @RequestParam(name = "regenerate", defaultValue = "false") boolean regenerate) {
        int max = matchProperties.maxLimit();
        int effectiveLimit = limit == null ? matchProperties.defaultLimit() : limit;
        if (effectiveLimit < 1 || effectiveLimit > max) {
            throw new InvalidParameterException("limit", "limit must be between 1 and " + max + ".");
        }
        if (Double.isNaN(minScore) || minScore < 0 || minScore > 1) {
            throw new InvalidParameterException("minScore", ApiParams.MIN_SCORE_MESSAGE);
        }
        return matchService.getMatches(id, page, effectiveLimit, minScore, regenerate);
    }

    @PostMapping("/matches/recompute")
    public ResponseEntity<RecomputeRunView> recompute(
            @RequestParam(name = "onlyStale", defaultValue = "false") boolean onlyStale) {
        RecomputeRunView run = recomputeService.start(onlyStale);
        return ResponseEntity.accepted()
                .location(URI.create("/api/matches/recompute/" + run.runId()))
                .body(run);
    }

    @GetMapping("/matches/recompute/{runId}")
    public RecomputeRunView run(@PathVariable("runId") UUID runId) {
        return recomputeService.find(runId)
                .orElseThrow(() -> NotFoundException.recomputeRun(runId, recomputeService.historySize()));
    }
}
