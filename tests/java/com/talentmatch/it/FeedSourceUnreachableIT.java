package com.talentmatch.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.talentmatch.support.AbstractApiIT;
import com.talentmatch.support.Api.Res;
import org.junit.jupiter.api.Test;

/** §5.1: an unreachable provider (the base context points every provider at a closed port). */
class FeedSourceUnreachableIT extends AbstractApiIT {

    @Test
    void unreachableHostIsCreatedWithWarning() {
        Res r = api.post("/api/feed/sources", "{\"kind\":\"LEVER\",\"boardToken\":\"acme\"}");
        assertThat(r.status()).as("%s", r).isEqualTo(201);
        assertThat(r.json().get("warnings").size()).isEqualTo(1);
        assertThat(r.json().get("warnings").get(0).asText())
                .isEqualTo("Couldn't reach Lever to check the board; it will be checked on the first poll.");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM feed_source", Integer.class)).isEqualTo(1);
    }
}
