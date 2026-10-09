package com.talentmatch.web.controller;

import com.talentmatch.preferences.PreferencesService;
import com.talentmatch.web.dto.PreferencesRequest;
import com.talentmatch.web.dto.PreferencesResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The owner's hand-entered job preferences (filters for the job feed). */
@RestController
@RequestMapping("/api/preferences")
public class PreferencesController {

    private final PreferencesService preferencesService;

    public PreferencesController(PreferencesService preferencesService) {
        this.preferencesService = preferencesService;
    }

    @GetMapping
    public PreferencesResponse get() {
        return PreferencesResponse.of(preferencesService.get());
    }

    @PutMapping
    public PreferencesResponse save(@RequestBody PreferencesRequest request) {
        return PreferencesResponse.of(preferencesService.save(request == null ? null : request.preferences()));
    }
}
