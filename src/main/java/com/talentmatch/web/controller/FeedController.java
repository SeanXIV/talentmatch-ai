package com.talentmatch.web.controller;

import com.talentmatch.feed.FeedSourceService;
import com.talentmatch.feed.FeedSourceState;
import com.talentmatch.feed.source.SourceKind;
import com.talentmatch.service.Paging;
import com.talentmatch.web.dto.FeedSourceRequest;
import com.talentmatch.web.dto.FeedSourceResponse;
import com.talentmatch.web.dto.FeedSourceUpdateRequest;
import com.talentmatch.web.dto.PageResponse;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.net.URI;
import java.util.UUID;
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
 * The job feed (Phase 5). Step 5: the watchlist of sources. Polling ({@code POST …/poll}), the feed
 * jobs and the status endpoint arrive with steps 6 and 7.
 */
@RestController
@RequestMapping("/api/feed")
@Validated
public class FeedController {

    private final FeedSourceService sourceService;

    public FeedController(FeedSourceService sourceService) {
        this.sourceService = sourceService;
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
}
