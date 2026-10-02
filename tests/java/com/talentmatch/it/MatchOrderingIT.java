package com.talentmatch.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.talentmatch.support.AbstractApiIT;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** §8.4 Ordering (score desc, fullName, id), limit/page/minScore, ranks across pages, rounding. */
class MatchOrderingIT extends AbstractApiIT {

    UUID job;
    List<UUID> zeds;

    @BeforeEach
    void data() {
        skill("Java");
        skill("SQL");
        skill("Docker");
        // 3 required skills = 30 points
        job = job("Backend Engineer", "Acme", "Java", "SQL", "Docker");
        candidate("Carol", "carol@example.com", "Java", "SQL", "Docker");       // 1.0
        candidate("Bob", "bob@example.com", "Java", "SQL");                     // 0.6667
        candidate("Alice", "alice@example.com", "SQL", "Docker");               // 0.6667
        candidate("Dave", "dave@example.com", "Docker");                        // 0.3333
        zeds = new ArrayList<>(List.of(
                candidate("Zed", "zed1@example.com", "Java"),                   // 0.3333 (same name)
                candidate("Zed", "zed2@example.com", "SQL"),
                candidate("Zed", "zed3@example.com", "Docker")));
        candidate("Eve", "eve@example.com");                                    // 0.0
        // PostgreSQL orders uuid bytewise, which equals ordering of the lowercase hex text.
        zeds.sort(Comparator.comparing(UUID::toString));
    }

    @Test
    void fullOrderWithTiesBrokenByNameThenId() {
        JsonNode page = matches(job);
        assertThat(candidateNames(page)).containsExactly("Carol", "Alice", "Bob", "Dave", "Zed", "Zed", "Zed", "Eve");
        assertThat(candidateIds(page).subList(4, 7)).containsExactlyElementsOf(zeds);
        List<Integer> ranks = new ArrayList<>();
        page.get("matches").forEach(m -> ranks.add(m.get("rank").asInt()));
        assertThat(ranks).containsExactly(1, 2, 3, 4, 5, 6, 7, 8);

        JsonNode alice = page.get("matches").get(1);
        assertThat(alice.get("score").asDouble()).isEqualTo(0.6667);
        assertThat(alice.get("scorePercent").asInt()).isEqualTo(67);
        assertThat(alice.get("summary").asText()).isEqualTo("Matches 2 of 3 required skills; missing: Java.");
        JsonNode dave = page.get("matches").get(3);
        assertThat(dave.get("score").asDouble()).isEqualTo(0.3333);
        assertThat(dave.get("scorePercent").asInt()).isEqualTo(33);
        assertThat(dave.get("summary").asText()).isEqualTo("Matches 1 of 3 required skills; missing: Java, SQL.");
        // the raw stored score is not rounded
        Double stored = jdbc.queryForObject("SELECT max(score) FROM job_match WHERE job_id = ? AND score < 1",
                Double.class, job);
        assertThat(stored).isEqualTo(20 / 30.0);
    }

    @Test
    void pagesAreStableAndRanksContinue() {
        JsonNode all = matches(job, "limit=100");
        List<UUID> expected = candidateIds(all);
        List<UUID> paged = new ArrayList<>();
        for (int p = 0; p < 3; p++) {
            JsonNode page = matches(job, "limit=3&page=" + p);
            assertThat(page.get("page").asInt()).isEqualTo(p);
            assertThat(page.get("limit").asInt()).isEqualTo(3);
            assertThat(page.get("totalElements").asLong()).isEqualTo(8);
            assertThat(page.get("totalPages").asInt()).isEqualTo(3);
            for (int i = 0; i < page.get("matches").size(); i++) {
                assertThat(page.get("matches").get(i).get("rank").asInt()).isEqualTo(p * 3 + i + 1);
            }
            paged.addAll(candidateIds(page));
        }
        assertThat(paged).containsExactlyElementsOf(expected);
        assertThat(matches(job, "limit=3&page=2").get("matches").size()).isEqualTo(2);

        JsonNode beyond = matches(job, "limit=3&page=50");
        assertThat(beyond.get("matches").size()).isZero();
        assertThat(beyond.get("totalElements").asLong()).isEqualTo(8);
        assertThat(beyond.get("reason").isNull()).isTrue();
    }

    @Test
    void defaultLimitIsTen() {
        for (int i = 0; i < 5; i++) {
            candidate("Extra " + i, "extra" + i + "@example.com", "Java");
        }
        JsonNode page = matches(job);
        assertThat(page.get("limit").asInt()).isEqualTo(10);
        assertThat(page.get("matches").size()).isEqualTo(10);
        assertThat(page.get("totalElements").asLong()).isEqualTo(13);
        assertThat(page.get("totalPages").asInt()).isEqualTo(2);
    }

    @Test
    void minScoreFilters() {
        JsonNode half = matches(job, "minScore=0.5");
        assertThat(candidateNames(half)).containsExactly("Carol", "Alice", "Bob");
        assertThat(half.get("totalElements").asLong()).isEqualTo(3);

        JsonNode one = matches(job, "minScore=1");
        assertThat(candidateNames(one)).containsExactly("Carol");

        JsonNode zero = matches(job, "minScore=0");
        assertThat(zero.get("totalElements").asLong()).isEqualTo(8);

        JsonNode nonzero = matches(job, "minScore=0.0001&limit=2&page=1");
        assertThat(nonzero.get("totalElements").asLong()).isEqualTo(7);
        assertThat(candidateNames(nonzero)).containsExactly("Bob", "Dave");
        assertThat(nonzero.at("/matches/0/rank").asInt()).isEqualTo(3);

        // nobody qualifies but candidates exist: empty, no NO_CANDIDATES reason
        api.put("/api/candidates/" + candidateIds(one).get(0),
                candidateBody("Carol", "carol@example.com", null, "Java", "SQL"));
        JsonNode none = matches(job, "minScore=1");
        assertThat(none.get("matches").size()).isZero();
        assertThat(none.get("totalElements").asLong()).isZero();
        assertThat(none.get("totalPages").asInt()).isZero();
        assertThat(none.get("matchable").asBoolean()).isTrue();
        assertThat(none.get("reason").isNull()).isTrue();
    }
}
