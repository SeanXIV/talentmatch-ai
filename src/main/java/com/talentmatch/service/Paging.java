package com.talentmatch.service;

import com.talentmatch.service.exception.InvalidParameterException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

/** Shared list paging rules (bounds are validated in the controllers; this guards overflow). */
public final class Paging {

    public static final int DEFAULT_SIZE = 20;
    public static final int MAX_SIZE = 100;

    private Paging() {
    }

    /** Unsorted page request (ordering is fixed in each query) for an in-range page and size. */
    public static Pageable of(int page, int size) {
        if ((long) page * size > Integer.MAX_VALUE) {
            throw new InvalidParameterException("page", "page is too large for this page size.");
        }
        return PageRequest.of(page, size);
    }
}
