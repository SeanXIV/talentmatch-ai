package com.talentmatch.web.dto;

import java.util.List;
import java.util.function.Function;
import org.springframework.data.domain.Page;

/**
 * Page of list results.
 *
 * @param content       items on this page
 * @param page          zero-based page index
 * @param size          requested page size
 * @param totalPages    total number of pages
 * @param totalElements total number of items
 */
public record PageResponse<T>(List<T> content, int page, int size, int totalPages, long totalElements) {

    public static <S, T> PageResponse<T> of(Page<S> page, Function<S, T> mapper) {
        return new PageResponse<>(page.getContent().stream().map(mapper).toList(),
                page.getNumber(), page.getSize(), page.getTotalPages(), page.getTotalElements());
    }
}
