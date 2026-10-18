package com.talentmatch.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** {@link UuidOrder}: PostgreSQL's unsigned uuid order (the PG comparison itself is in DatabaseOrderIT). */
class UuidOrderTest {

    private static final UUID ZERO = UUID.fromString("00000000-0000-0000-0000-000000000000");
    private static final UUID LOW_MAX = UUID.fromString("7fffffff-ffff-ffff-ffff-ffffffffffff");
    private static final UUID HIGH_BIT = UUID.fromString("80000000-0000-0000-0000-000000000000");
    private static final UUID MAX = UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff");
    private static final UUID LSB_LOW = UUID.fromString("12345678-0000-0000-7fff-ffffffffffff");
    private static final UUID LSB_HIGH = UUID.fromString("12345678-0000-0000-8000-000000000000");

    @Test
    void comparesUnsignedOnBothHalves() {
        assertThat(UuidOrder.COMPARATOR.compare(LOW_MAX, HIGH_BIT)).isNegative();
        assertThat(LOW_MAX.compareTo(HIGH_BIT)).as("UUID.compareTo is signed").isPositive();
        assertThat(UuidOrder.COMPARATOR.compare(LSB_LOW, LSB_HIGH)).isNegative();
        assertThat(LSB_LOW.compareTo(LSB_HIGH)).isPositive();
        assertThat(UuidOrder.COMPARATOR.compare(ZERO, MAX)).isNegative();
        assertThat(UuidOrder.COMPARATOR.compare(MAX, ZERO)).isPositive();
        assertThat(UuidOrder.COMPARATOR.compare(HIGH_BIT, UUID.fromString(HIGH_BIT.toString()))).isZero();
    }

    @Test
    void matchesLexicographicOrderOfTheCanonicalString() {
        // Lower-case hex strings of equal length sort exactly like PostgreSQL's byte-wise uuid order.
        Random random = new Random(7);
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < 5000; i++) {
            ids.add(new UUID(random.nextLong(), random.nextLong()));
        }
        ids.addAll(List.of(ZERO, LOW_MAX, HIGH_BIT, MAX, LSB_LOW, LSB_HIGH));
        List<UUID> byString = new ArrayList<>(ids);
        byString.sort((a, b) -> a.toString().compareTo(b.toString()));
        List<UUID> byComparator = new ArrayList<>(ids);
        byComparator.sort(UuidOrder.COMPARATOR);
        assertThat(byComparator).isEqualTo(byString);
    }

    @Test
    void sortedDistinctSortsAndDropsDuplicates() {
        List<UUID> in = List.of(MAX, HIGH_BIT, ZERO, HIGH_BIT, LOW_MAX, MAX);
        assertThat(UuidOrder.sortedDistinct(in)).containsExactly(ZERO, LOW_MAX, HIGH_BIT, MAX);
        assertThat(UuidOrder.sortedDistinct(Set.of(LSB_HIGH, LSB_LOW))).containsExactly(LSB_LOW, LSB_HIGH);
        assertThat(UuidOrder.sortedDistinct(List.of())).isEmpty();
    }

    @Test
    void sortedDistinctDoesNotModifyItsInput() {
        List<UUID> in = new ArrayList<>(List.of(MAX, ZERO, MAX));
        UuidOrder.sortedDistinct(in);
        assertThat(in).containsExactly(MAX, ZERO, MAX);
    }

    @Test
    void isStrictlyAscending() {
        assertThat(UuidOrder.isStrictlyAscending(List.of())).isTrue();
        assertThat(UuidOrder.isStrictlyAscending(List.of(MAX))).isTrue();
        assertThat(UuidOrder.isStrictlyAscending(List.of(ZERO, LOW_MAX, HIGH_BIT, MAX))).isTrue();
        assertThat(UuidOrder.isStrictlyAscending(List.of(LSB_LOW, LSB_HIGH))).isTrue();
        // duplicates are not strictly ascending
        assertThat(UuidOrder.isStrictlyAscending(List.of(ZERO, ZERO))).isFalse();
        assertThat(UuidOrder.isStrictlyAscending(List.of(ZERO, HIGH_BIT, HIGH_BIT, MAX))).isFalse();
        // descending / signed (UUID.compareTo) order is rejected
        assertThat(UuidOrder.isStrictlyAscending(List.of(MAX, ZERO))).isFalse();
        assertThat(UuidOrder.isStrictlyAscending(List.of(HIGH_BIT, LOW_MAX))).isFalse();
        assertThat(UuidOrder.isStrictlyAscending(List.of(LSB_HIGH, LSB_LOW))).isFalse();
        // unsorted only at the end
        assertThat(UuidOrder.isStrictlyAscending(List.of(ZERO, LOW_MAX, MAX, HIGH_BIT))).isFalse();
    }

    @Test
    void sortedDistinctOutputIsStrictlyAscending() {
        Random random = new Random(11);
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < 1000; i++) {
            UUID id = new UUID(random.nextLong(), random.nextLong());
            ids.add(id);
            ids.add(id);
        }
        Collections.shuffle(ids, random);
        List<UUID> sorted = UuidOrder.sortedDistinct(ids);
        assertThat(sorted).hasSize(1000);
        assertThat(UuidOrder.isStrictlyAscending(sorted)).isTrue();
    }
}
