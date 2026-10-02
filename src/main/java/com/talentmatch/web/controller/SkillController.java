package com.talentmatch.web.controller;

import com.talentmatch.service.Paging;
import com.talentmatch.service.SkillService;
import com.talentmatch.web.dto.PageResponse;
import com.talentmatch.web.dto.SkillRequest;
import com.talentmatch.web.dto.SkillResponse;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Skill vocabulary: list/search, read, create. */
@RestController
@RequestMapping("/api/skills")
@Validated
public class SkillController {

    private final SkillService skillService;

    public SkillController(SkillService skillService) {
        this.skillService = skillService;
    }

    @GetMapping
    public PageResponse<SkillResponse> list(
            @RequestParam(name = "q", required = false) String q,
            @RequestParam(name = "page", defaultValue = "0")
            @Min(value = 0, message = ApiParams.PAGE_MESSAGE) int page,
            @RequestParam(name = "size", defaultValue = "" + Paging.DEFAULT_SIZE)
            @Min(value = 1, message = ApiParams.SIZE_MESSAGE)
            @Max(value = Paging.MAX_SIZE, message = ApiParams.SIZE_MESSAGE) int size) {
        return skillService.list(q, page, size);
    }

    @GetMapping("/{id}")
    public SkillResponse get(@PathVariable("id") UUID id) {
        return skillService.get(id);
    }

    @PostMapping
    public ResponseEntity<SkillResponse> create(@RequestBody SkillRequest request) {
        SkillResponse created = skillService.create(request);
        return ResponseEntity.created(URI.create("/api/skills/" + created.id())).body(created);
    }
}
