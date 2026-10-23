package com.talentmatch.application;

import java.time.Instant;

/**
 * One status change in an application's history (§5.1 {@code history}).
 *
 * @param from   null for the creation event
 * @param forced true when the move was sent with {@code force=true}
 */
public record EventView(ApplicationStatus from, ApplicationStatus to, boolean forced, Instant at) {
}
