package com.talentmatch.web.controller;

import com.talentmatch.service.JobService;
import com.talentmatch.service.Paging;
import com.talentmatch.web.dto.JobDetailResponse;
import com.talentmatch.web.dto.JobRequest;
import com.talentmatch.web.dto.JobSummaryResponse;
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

/** Jobs: list, read, create, full replace, delete. Matches live in {@link MatchController}. */
@RestController
@RequestMapping("/api/jobs")
@Validated
public class JobController {

    private final JobService jobService;

    public JobController(JobService jobService) {
        this.jobService = jobService;
    }

    @GetMapping
    public PageResponse<JobSummaryResponse> list(
            @RequestParam(name = "page", defaultValue = "0")
            @Min(value = 0, message = ApiParams.PAGE_MESSAGE) int page,
            @RequestParam(name = "size", defaultValue = "" + Paging.DEFAULT_SIZE)
            @Min(value = 1, message = ApiParams.SIZE_MESSAGE)
            @Max(value = Paging.MAX_SIZE, message = ApiParams.SIZE_MESSAGE) int size,
            @RequestParam(name = "skill", required = false) String skill) {
        return jobService.list(page, size, skill);
    }

    @GetMapping("/{id}")
    public JobDetailResponse get(@PathVariable("id") UUID id) {
        return jobService.get(id);
    }

    @PostMapping
    public ResponseEntity<JobDetailResponse> create(@RequestBody JobRequest request) {
        JobDetailResponse created = jobService.create(request);
        return ResponseEntity.created(URI.create("/api/jobs/" + created.id())).body(created);
    }

    @PutMapping("/{id}")
    public JobDetailResponse update(@PathVariable("id") UUID id, @RequestBody JobRequest request) {
        return jobService.update(id, request);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable("id") UUID id) {
        jobService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
