package com.talentmatch.feed;

import static org.assertj.core.api.Assertions.assertThat;

import com.talentmatch.support.AbstractApiIT;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** {@link FeedJobRepository#DATABASE_ORDER} must equal PostgreSQL's {@code ORDER BY uuid} (the lock order). */
class DatabaseOrderIT extends AbstractApiIT {

    private List<UUID> pgOrder(List<UUID> ids) {
        return jdbc.execute((java.sql.Connection c) -> {
            try (var ps = c.prepareStatement("SELECT u FROM unnest(?::uuid[]) AS u ORDER BY u")) {
                ps.setArray(1, c.createArrayOf("uuid", ids.toArray()));
                List<UUID> out = new ArrayList<>();
                try (var rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(rs.getObject(1, UUID.class));
                    }
                }
                return out;
            }
        });
    }

    @Test
    void matchesPostgresOrderIncludingHighBitIds() {
        Random random = new Random(42);
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < 2000; i++) {
            ids.add(new UUID(random.nextLong(), random.nextLong()));      // about half have the high bit set
            ids.add(UUID.randomUUID());
        }
        for (String s : new String[] {"00000000-0000-0000-0000-000000000000", "7fffffff-ffff-ffff-ffff-ffffffffffff",
                "80000000-0000-0000-0000-000000000000", "ffffffff-ffff-ffff-ffff-ffffffffffff",
                "12345678-0000-0000-7fff-ffffffffffff", "12345678-0000-0000-8000-000000000000",
                "12345678-0000-0000-ffff-ffffffffffff"}) {
            ids.add(UUID.fromString(s));
        }
        Collections.shuffle(ids, random);
        List<UUID> expected = pgOrder(ids);
        assertThat(FeedJobRepository.sortedForDatabase(ids)).isEqualTo(expected);
        List<UUID> signed = new ArrayList<>(ids);
        Collections.sort(signed);
        assertThat(signed).as("UUID.compareTo would disagree with PostgreSQL").isNotEqualTo(expected);
    }
}
