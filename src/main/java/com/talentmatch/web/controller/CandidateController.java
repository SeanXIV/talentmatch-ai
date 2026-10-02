package com.talentmatch.web.controller;

import com.talentmatch.service.CandidateService;
import com.talentmatch.service.Paging;
import com.talentmatch.web.dto.CandidateDetailResponse;
import com.talentmatch.web.dto.CandidateRequest;
import com.talentmatch.web.dto.CandidateSummaryResponse;
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

/** Candidates: list, read, create, full replace, delete. */
@RestController
@RequestMapping("/api/candidates")
@Validated
public class CandidateController {

    private final CandidateService candidateService;

    public CandidateController(CandidateService candidateService) {
        this.candidateService = candidateService;
    }

    @GetMapping
    public PageResponse<CandidateSummaryResponse> list(
            @RequestParam(name = "page", defaultValue = "0")
            @Min(value = 0, message = ApiParams.PAGE_MESSAGE) int page,
            @RequestParam(name = "size", defaultValue = "" + Paging.DEFAULT_SIZE)
            @Min(value = 1, message = ApiParams.SIZE_MESSAGE)
            @Max(value = Paging.MAX_SIZE, message = ApiParams.SIZE_MESSAGE) int size,
            @RequestParam(name = "skill", required = false) String skill) {
        return candidateService.list(page, size, skill);
    }

    @GetMapping("/{id}")
    public CandidateDetailResponse get(@PathVariable("id") UUID id) {
        return candidateService.get(id);
    }

    @PostMapping
    public ResponseEntity<CandidateDetailResponse> create(@RequestBody CandidateRequest request) {
        CandidateDetailResponse created = candidateService.create(request);
        return ResponseEntity.created(URI.create("/api/candidates/" + created.id())).body(created);
    }

    @PutMapping("/{id}")
    public CandidateDetailResponse update(@PathVariable("id") UUID id, @RequestBody CandidateRequest request) {
        return candidateService.update(id, request);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable("id") UUID id) {
        candidateService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
