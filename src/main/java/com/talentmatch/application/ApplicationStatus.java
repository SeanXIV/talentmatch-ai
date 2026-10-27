package com.talentmatch.application;

import java.util.Arrays;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Tracker status of an application (decision m). Must match V7's {@code ck_application_status}.
 * Transitions are enforced (§4.1); {@code force=true} on the update allows any move, to correct
 * mistakes, and is recorded in the history. REJECTED and WITHDRAWN are terminal.
 */
public enum ApplicationStatus {
    NEW,
    SHORTLISTED,
    APPLIED,
    INTERVIEW,
    OFFER,
    REJECTED,
    WITHDRAWN,
    /** Not interested (Phase 5 decision h: dismissing comes with the tracker). */
    DISMISSED;

    /** "NEW, SHORTLISTED, APPLIED, INTERVIEW, OFFER, REJECTED, WITHDRAWN, DISMISSED" (for messages). */
    public static final String NAMES = Arrays.stream(values()).map(Enum::name).collect(Collectors.joining(", "));

    private static final Map<ApplicationStatus, List<ApplicationStatus>> NEXT = new EnumMap<>(ApplicationStatus.class);

    static {
        NEXT.put(NEW, ordered(SHORTLISTED, APPLIED, DISMISSED));
        NEXT.put(SHORTLISTED, ordered(NEW, APPLIED, DISMISSED));
        NEXT.put(APPLIED, ordered(INTERVIEW, OFFER, REJECTED, WITHDRAWN));
        NEXT.put(INTERVIEW, ordered(OFFER, REJECTED, WITHDRAWN));
        NEXT.put(OFFER, ordered(REJECTED, WITHDRAWN));
        NEXT.put(REJECTED, List.of());
        NEXT.put(WITHDRAWN, List.of());
        NEXT.put(DISMISSED, ordered(NEW, SHORTLISTED));
    }

    /**
     * True for the statuses that need an applied date and may record sent versions: APPLIED,
     * INTERVIEW, OFFER, REJECTED and WITHDRAWN.
     */
    public boolean applied() {
        return switch (this) {
            case APPLIED, INTERVIEW, OFFER, REJECTED, WITHDRAWN -> true;
            case NEW, SHORTLISTED, DISMISSED -> false;
        };
    }

    /** The statuses this one may move to without {@code force}, in declaration order (empty when terminal). */
    public List<ApplicationStatus> allowedTransitions() {
        return NEXT.get(this);
    }

    /** True if moving to {@code target} needs no {@code force}. Staying in the same status is not a move. */
    public boolean canMoveTo(ApplicationStatus target) {
        return NEXT.get(this).contains(target);
    }

    /** The statuses a new application may start in. */
    public boolean initial() {
        return this == NEW || this == SHORTLISTED;
    }

    /**
     * Case-insensitive, trimmed parse.
     *
     * @return null for a null, blank or unknown value
     */
    public static ApplicationStatus parse(String value) {
        if (value == null) {
            return null;
        }
        String v = value.strip().toUpperCase(Locale.ROOT);
        for (ApplicationStatus status : values()) {
            if (status.name().equals(v)) {
                return status;
            }
        }
        return null;
    }

    private static List<ApplicationStatus> ordered(ApplicationStatus first, ApplicationStatus... rest) {
        return List.copyOf(EnumSet.of(first, rest));
    }
}
