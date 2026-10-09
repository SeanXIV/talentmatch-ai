package com.talentmatch.web.controller;

import com.talentmatch.feed.FeedJobService;
import com.talentmatch.feed.FeedPollService;
import com.talentmatch.feed.FeedSourceService;
import com.talentmatch.feed.FeedSourceState;
import com.talentmatch.feed.FeedStatusService;
import com.talentmatch.feed.source.SourceKind;
import com.talentmatch.service.Paging;
import com.talentmatch.service.exception.InvalidParameterException;
import com.talentmatch.web.dto.FeedJobResponse;
import com.talentmatch.web.dto.FeedPollResponse;
import com.talentmatch.web.dto.FeedSourceRequest;
import com.talentmatch.web.dto.FeedSourceResponse;
import com.talentmatch.web.dto.FeedSourceUpdateRequest;
import com.talentmatch.web.dto.FeedStatusResponse;
import com.talentmatch.web.dto.PageResponse;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.net.URI;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The job feed (Phase 5): the watchlist of sources (step 5), polling on request and the feed status
 * (step 6), and the feed jobs (step 7).
 */
@RestController
@RequestMapping("/api/feed")
@Validated
public class FeedController {

    private final FeedSourceService sourceService;
    private final FeedPollService pollService;
    private final FeedStatusService statusService;
    private final FeedJobService jobService;

    public FeedController(FeedSourceService sourceService, FeedPollService pollService,
                          FeedStatusService statusService, FeedJobService jobService) {
        this.sourceService = sourceService;
        this.pollService = pollService;
        this.statusService = statusService;
        this.jobService = jobService;
    }

    @GetMapping("/sources")
    public PageResponse<FeedSourceResponse> listSources(
            @RequestParam(name = "page", defaultValue = "0")
            @Min(value = 0, message = ApiParams.PAGE_MESSAGE) int page,
            @RequestParam(name = "size", defaultValue = "" + Paging.DEFAULT_SIZE)
            @Min(value = 1, message = ApiParams.SIZE_MESSAGE)
            @Max(value = Paging.MAX_SIZE, message = ApiParams.SIZE_MESSAGE) int size,
            @RequestParam(name = "kind", required = false) SourceKind kind,
            @RequestParam(name = "state", required = false) FeedSourceState state) {
        return PageResponse.of(sourceService.list(kind, state, page, size), FeedSourceResponse::of);
    }

    @GetMapping("/sources/{id}")
    public FeedSourceResponse getSource(@PathVariable("id") UUID id) {
        return FeedSourceResponse.of(sourceService.get(id));
    }

    @PostMapping("/sources")
    public ResponseEntity<FeedSourceResponse> createSource(@RequestBody FeedSourceRequest request) {
        FeedSourceResponse created = FeedSourceResponse.of(
                sourceService.create(request == null ? null : request.toNewSource()));
        return ResponseEntity.created(URI.create("/api/feed/sources/" + created.id())).body(created);
    }

    @PutMapping("/sources/{id}")
    public FeedSourceResponse updateSource(@PathVariable("id") UUID id, @RequestBody FeedSourceUpdateRequest request) {
        return FeedSourceResponse.of(sourceService.update(id, request == null ? null : request.toChanges()));
    }

    @DeleteMapping("/sources/{id}")
    public ResponseEntity<Void> deleteSource(@PathVariable("id") UUID id) {
        sourceService.delete(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/sources/{id}/poll")
    public ResponseEntity<FeedPollResponse> pollSource(@PathVariable("id") UUID id) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(FeedPollResponse.of(pollService.pollNow(id)));
    }

    @GetMapping("/status")
    public FeedStatusResponse status() {
        return FeedStatusResponse.of(statusService.status());
    }

    // ------------------------------------------------------------------ feed jobs (step 7)

    @GetMapping("/jobs")
    public PageResponse<FeedJobResponse> listJobs(
            @RequestParam(name = "page", defaultValue = "0")
            @Min(value = 0, message = ApiParams.PAGE_MESSAGE) int page,
            @RequestParam(name = "size", defaultValue = "" + Paging.DEFAULT_SIZE)
            @Min(value = 1, message = ApiParams.SIZE_MESSAGE)
            @Max(value = Paging.MAX_SIZE, message = ApiParams.SIZE_MESSAGE) int size,
            @RequestParam(name = "since", required = false) String since,
            @RequestParam(name = "minScore", required = false) Double minScore,
            @RequestParam(name = "includeFiltered", defaultValue = "false") boolean includeFiltered,
            @RequestParam(name = "includeClosed", defaultValue = "false") boolean includeClosed,
            @RequestParam(name = "includeBaseline", defaultValue = "false") boolean includeBaseline) {
        if (minScore != null && (minScore.isNaN() || minScore < 0 || minScore > 1)) {
            throw new InvalidParameterException("minScore", ApiParams.MIN_SCORE_MESSAGE);
        }
        FeedJobService.Filters filters = new FeedJobService.Filters(instant(since), minScore, includeFiltered,
                includeClosed, includeBaseline);
        return PageResponse.of(jobService.list(filters, page, size), FeedJobResponse::of);
    }

    @GetMapping("/jobs/{jobId}")
    public FeedJobResponse getJob(@PathVariable("jobId") UUID jobId) {
        return FeedJobResponse.of(jobService.get(jobId));
    }

    /** {@code since}: an ISO-8601 instant ("2026-10-08T06:00:00Z") or offset date-time ("…+02:00"). */
    private static Instant instant(String since) {
        if (since == null || since.isBlank()) {
            return null;
        }
        String s = since.strip();
        try {
            return Instant.parse(s);
        } catch (DateTimeParseException e) {
            try {
                return OffsetDateTime.parse(s).toInstant();
            } catch (DateTimeParseException again) {
                throw new InvalidParameterException("since",
                        "since must be an ISO-8601 instant such as 2026-10-08T06:00:00Z.");
            }
        }
    }
}
