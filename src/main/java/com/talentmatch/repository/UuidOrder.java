package com.talentmatch.repository;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * PostgreSQL's ordering of {@code uuid} values (unsigned, byte by byte). Lock acquisition in this
 * order matches {@code ORDER BY id} in SQL, which keeps multi-row lockers deadlock-free.
 * {@link UUID#compareTo} compares signed halves and would disagree for ids with the high bit set.
 */
public final class UuidOrder {

    /** Compares two uuids the way PostgreSQL does. */
    public static final Comparator<UUID> COMPARATOR = (a, b) -> {
        int c = Long.compareUnsigned(a.getMostSignificantBits(), b.getMostSignificantBits());
        return c != 0 ? c : Long.compareUnsigned(a.getLeastSignificantBits(), b.getLeastSignificantBits());
    };

    private UuidOrder() {
    }

    /** Distinct ids in PostgreSQL's {@code uuid} order. */
    public static List<UUID> sortedDistinct(Collection<UUID> ids) {
        return ids.stream().distinct().sorted(COMPARATOR).toList();
    }

    /** True if the ids are strictly ascending in PostgreSQL's {@code uuid} order (so also distinct). */
    public static boolean isStrictlyAscending(List<UUID> ids) {
        for (int i = 1; i < ids.size(); i++) {
            if (COMPARATOR.compare(ids.get(i - 1), ids.get(i)) >= 0) {
                return false;
            }
        }
        return true;
    }
}
