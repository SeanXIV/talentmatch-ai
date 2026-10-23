package com.talentmatch.web.controller;

import com.talentmatch.application.ApplicationService;
import com.talentmatch.application.ApplicationSort;
import com.talentmatch.application.ApplicationStatus;
import com.talentmatch.application.ApplicationSummaryView;
import com.talentmatch.application.ApplicationView;
import com.talentmatch.application.GenerationService;
import com.talentmatch.application.GenerationView;
import com.talentmatch.service.Paging;
import com.talentmatch.service.exception.InvalidParameterException;
import com.talentmatch.web.dto.ApplicationCreateRequest;
import com.talentmatch.web.dto.ApplicationUpdateRequest;
import com.talentmatch.web.dto.GenerationRequest;
import com.talentmatch.web.dto.PageResponse;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.net.URI;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
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
 * The application tracker and document generation (Phase 7 §5.2, §5.4). PII: notes and tailored
 * documents; no auth, loopback only, like {@code /api/profile}.
 *
 * <p>Steps 1 and 5: the tracker and generation endpoints. Documents arrive in step 6.
 */
@RestController
@RequestMapping("/api/applications")
@Validated
public class ApplicationController {

    static final String STATUS_FILTER_MESSAGE = "status must be a comma-separated list of "
            + ApplicationStatus.NAMES + ".";
    static final String SORT_MESSAGE = "sort must be updated or created.";

    private final ApplicationService applicationService;
    private final GenerationService generationService;

    public ApplicationController(ApplicationService applicationService, GenerationService generationService) {
        this.applicationService = applicationService;
        this.generationService = generationService;
    }

    @GetMapping
    public PageResponse<ApplicationSummaryView> list(
            @RequestParam(name = "page", defaultValue = "0")
            @Min(value = 0, message = ApiParams.PAGE_MESSAGE) int page,
            @RequestParam(name = "size", defaultValue = "" + Paging.DEFAULT_SIZE)
            @Min(value = 1, message = ApiParams.SIZE_MESSAGE)
            @Max(value = Paging.MAX_SIZE, message = ApiParams.SIZE_MESSAGE) int size,
            @RequestParam(name = "status", required = false) List<String> status,
            @RequestParam(name = "sort", required = false) String sort) {
        return PageResponse.of(applicationService.list(statuses(status), order(sort), page, size), s -> s);
    }

    @GetMapping("/{id}")
    public ApplicationView get(@PathVariable("id") UUID id) {
        return applicationService.get(id);
    }

    @PostMapping
    public ResponseEntity<ApplicationView> create(@RequestBody ApplicationCreateRequest request) {
        ApplicationView created = applicationService.create(request == null ? null : request.toNewApplication());
        return ResponseEntity.created(URI.create("/api/applications/" + created.id())).body(created);
    }

    @PutMapping("/{id}")
    public ApplicationView update(@PathVariable("id") UUID id, @RequestBody ApplicationUpdateRequest request) {
        return applicationService.update(id, request == null ? null : request.toChanges());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable("id") UUID id) {
        applicationService.delete(id);
        return ResponseEntity.noContent().build();
    }

    /** Queues an AI generation of the application's documents; poll the returned generation. */
    @PostMapping("/{id}/generations")
    public ResponseEntity<GenerationView> generate(@PathVariable("id") UUID id,
                                                   @RequestBody(required = false) GenerationRequest request) {
        GenerationView queued = generationService.request(id, request == null ? null : request.kinds(),
                request == null ? null : request.mentionNoticePeriod());
        return ResponseEntity.accepted()
                .location(URI.create("/api/applications/" + id + "/generations/" + queued.id()))
                .body(queued);
    }

    @GetMapping("/{id}/generations/{genId}")
    public GenerationView generation(@PathVariable("id") UUID id, @PathVariable("genId") UUID genId) {
        return generationService.get(id, genId);
    }

    /**
     * {@code status=NEW,APPLIED} or {@code status=NEW&status=APPLIED} (case-insensitive); blank entries
     * are ignored, and no value means every status.
     */
    static Set<ApplicationStatus> statuses(List<String> raw) {
        Set<ApplicationStatus> statuses = EnumSet.noneOf(ApplicationStatus.class);
        if (raw == null) {
            return statuses;
        }
        for (String value : raw) {
            if (value == null) {
                continue;
            }
            for (String token : value.split(",")) {
                if (token.isBlank()) {
                    continue;
                }
                ApplicationStatus parsed = ApplicationStatus.parse(token);
                if (parsed == null) {
                    throw new InvalidParameterException("status", STATUS_FILTER_MESSAGE);
                }
                statuses.add(parsed);
            }
        }
        return statuses;
    }

    /** {@code updated} (default) or {@code created}, case-insensitive. */
    static ApplicationSort order(String raw) {
        if (raw == null || raw.isBlank()) {
            return ApplicationSort.UPDATED;
        }
        return switch (raw.strip().toLowerCase(Locale.ROOT)) {
            case "updated" -> ApplicationSort.UPDATED;
            case "created" -> ApplicationSort.CREATED;
            default -> throw new InvalidParameterException("sort", SORT_MESSAGE);
        };
    }
}
