package com.talentmatch.application;

/** Order of {@code GET /api/applications}: {@code sort=updated} (default) or {@code sort=created}, newest first. */
public enum ApplicationSort {
    UPDATED("a.updated_at DESC, a.id"),
    CREATED("a.created_at DESC, a.id");

    /** The ORDER BY clause over {@code application a}. */
    final String sql;

    ApplicationSort(String sql) {
        this.sql = sql;
    }
}
