package com.talentmatch.web.controller;

import com.talentmatch.service.Paging;

/** Shared query-parameter validation messages (compile-time constants for annotations). */
final class ApiParams {

    static final String PAGE_MESSAGE = "page must be 0 or greater.";
    static final String SIZE_MESSAGE = "size must be between 1 and " + Paging.MAX_SIZE + ".";
    static final String MIN_SCORE_MESSAGE = "minScore must be between 0 and 1.";

    private ApiParams() {
    }
}
