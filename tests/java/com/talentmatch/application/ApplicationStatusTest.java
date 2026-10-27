package com.talentmatch.application;

import static com.talentmatch.application.ApplicationStatus.*;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** §9.1.1: the full transition matrix (§4.1), applied(), allowedTransitions ordering, parse. */
class ApplicationStatusTest {

    /** The §4.1 table, written out independently of the enum. */
    private static final Map<ApplicationStatus, Set<ApplicationStatus>> ALLOWED = new EnumMap<>(ApplicationStatus.class);

    static {
        ALLOWED.put(NEW, EnumSet.of(SHORTLISTED, APPLIED, DISMISSED));
        ALLOWED.put(SHORTLISTED, EnumSet.of(NEW, APPLIED, DISMISSED));
        ALLOWED.put(APPLIED, EnumSet.of(INTERVIEW, OFFER, REJECTED, WITHDRAWN));
        ALLOWED.put(INTERVIEW, EnumSet.of(OFFER, REJECTED, WITHDRAWN));
        ALLOWED.put(OFFER, EnumSet.of(REJECTED, WITHDRAWN));
        ALLOWED.put(REJECTED, EnumSet.noneOf(ApplicationStatus.class));
        ALLOWED.put(WITHDRAWN, EnumSet.noneOf(ApplicationStatus.class));
        ALLOWED.put(DISMISSED, EnumSet.of(NEW, SHORTLISTED));
    }

    @Test
    void theFullTransitionMatrix() {
        int allowed = 0;
        for (ApplicationStatus from : values()) {
            for (ApplicationStatus to : values()) {
                boolean expected = ALLOWED.get(from).contains(to);
                assertThat(from.canMoveTo(to)).as("%s -> %s", from, to).isEqualTo(expected);
                allowed += expected ? 1 : 0;
            }
            assertThat(from.canMoveTo(from)).as("staying is not a move: %s", from).isFalse();
        }
        assertThat(allowed).isEqualTo(17);
    }

    @Test
    void allowedTransitionsAreInDeclarationOrder() {
        for (ApplicationStatus from : values()) {
            List<ApplicationStatus> list = from.allowedTransitions();
            assertThat(list).as("%s", from).containsExactlyElementsOf(ALLOWED.get(from));
            assertThat(list).isSortedAccordingTo(Enum::compareTo);
        }
        assertThat(APPLIED.allowedTransitions()).containsExactly(INTERVIEW, OFFER, REJECTED, WITHDRAWN);
        assertThat(DISMISSED.allowedTransitions()).containsExactly(NEW, SHORTLISTED);
        assertThat(REJECTED.allowedTransitions()).isEmpty();
        assertThat(WITHDRAWN.allowedTransitions()).isEmpty();
    }

    @Test
    void appliedAndInitial() {
        assertThat(EnumSet.allOf(ApplicationStatus.class).stream().filter(ApplicationStatus::applied))
                .containsExactly(APPLIED, INTERVIEW, OFFER, REJECTED, WITHDRAWN);
        assertThat(EnumSet.allOf(ApplicationStatus.class).stream().filter(ApplicationStatus::initial))
                .containsExactly(NEW, SHORTLISTED);
    }

    @Test
    void namesAndParse() {
        assertThat(NAMES).isEqualTo("NEW, SHORTLISTED, APPLIED, INTERVIEW, OFFER, REJECTED, WITHDRAWN, DISMISSED");
        assertThat(parse(" applied ")).isEqualTo(APPLIED);
        assertThat(parse("Dismissed")).isEqualTo(DISMISSED);
        assertThat(parse("archived")).isNull();
        assertThat(parse("")).isNull();
        assertThat(parse(null)).isNull();
    }
}
