package com.talentmatch.web.controller;

import com.talentmatch.service.SkillAliasService;
import com.talentmatch.web.dto.SkillAliasRequest;
import com.talentmatch.web.dto.SkillAliasResponse;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Other names for a skill ("Postgres" → PostgreSQL). */
@RestController
@RequestMapping("/api/skills/{id}/aliases")
public class SkillAliasController {

    private final SkillAliasService aliasService;

    public SkillAliasController(SkillAliasService aliasService) {
        this.aliasService = aliasService;
    }

    @GetMapping
    public List<SkillAliasResponse> list(@PathVariable("id") UUID skillId) {
        return aliasService.list(skillId);
    }

    @PostMapping
    public ResponseEntity<SkillAliasResponse> create(@PathVariable("id") UUID skillId,
                                                     @RequestBody SkillAliasRequest request) {
        SkillAliasResponse created = aliasService.create(skillId, request);
        return ResponseEntity.created(URI.create("/api/skills/" + skillId + "/aliases/" + created.id())).body(created);
    }

    @DeleteMapping("/{aliasId}")
    public ResponseEntity<Void> delete(@PathVariable("id") UUID skillId, @PathVariable("aliasId") UUID aliasId) {
        aliasService.delete(skillId, aliasId);
        return ResponseEntity.noContent().build();
    }
}
