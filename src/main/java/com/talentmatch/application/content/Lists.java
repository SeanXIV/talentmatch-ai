package com.talentmatch.application.content;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** List handling for the content records: null becomes empty; null elements are kept (validators report them). */
final class Lists {

    private Lists() {
    }

    static <T> List<T> of(List<T> list) {
        return list == null || list.isEmpty() ? List.of() : Collections.unmodifiableList(new ArrayList<>(list));
    }
}
