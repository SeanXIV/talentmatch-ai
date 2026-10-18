package com.talentmatch.feed;

import static org.assertj.core.api.Assertions.assertThat;

import com.talentmatch.repository.UuidOrder;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** FeedJobRepository's lock order now delegates to {@link UuidOrder} (Phase 6 step 1 move). */
class FeedJobRepositoryOrderTest {

    @Test
    void databaseOrderIsTheSharedComparator() {
        assertThat(FeedJobRepository.DATABASE_ORDER).isSameAs(UuidOrder.COMPARATOR);
    }

    @Test
    void sortedForDatabaseEqualsUuidOrder() {
        List<UUID> in = List.of(UUID.fromString("ffffffff-0000-0000-0000-000000000000"),
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                UUID.fromString("80000000-0000-0000-0000-000000000000"),
                UUID.fromString("00000000-0000-0000-0000-000000000001"));
        assertThat(FeedJobRepository.sortedForDatabase(in)).isEqualTo(UuidOrder.sortedDistinct(in))
                .hasSize(3);
    }
}
