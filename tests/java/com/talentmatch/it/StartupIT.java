package com.talentmatch.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.talentmatch.support.AbstractApiIT;
import com.talentmatch.support.Api.Res;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;

/** §8.1 Startup: Flyway V1..V3, Hibernate validate, health; plus request-id handling. */
class StartupIT extends AbstractApiIT {

    @Autowired
    Environment environment;

    @Test
    void flywayAppliedV1ToV3AndHibernateValidates() {
        List<String> versions = jdbc.queryForList(
                "SELECT version FROM flyway_schema_history WHERE success AND version IS NOT NULL ORDER BY installed_rank",
                String.class);
        assertThat(versions).containsExactly("1", "2", "3");
        // The context started with ddl-auto=validate, so the entity mappings match V1.
        assertThat(environment.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_trigger WHERE tgname LIKE 'trg_%_skill_touch_%'",
                Integer.class)).isEqualTo(6);
        assertThat(environment.getProperty("spring.flyway.clean-disabled")).isEqualTo("true");
    }

    @Test
    void healthIsUpWithDatabaseAndProbes() {
        Res health = api.get("/actuator/health");
        assertThat(health.status()).as(health.toString()).isEqualTo(200);
        assertThat(health.json().get("status").asText()).isEqualTo("UP");
        assertThat(health.json().at("/components/db/status").asText()).isEqualTo("UP");
        // Phase 3: the AI component reports template-only mode when disabled
        assertThat(health.json().at("/components/ai/status").asText()).isEqualTo("UP");
        assertThat(health.json().at("/components/ai/details/mode").asText()).isEqualTo("template-only");
        for (String probe : List.of("/actuator/health/liveness", "/actuator/health/readiness")) {
            Res r = api.get(probe);
            assertThat(r.status()).as(probe + " " + r).isEqualTo(200);
            assertThat(r.json().get("status").asText()).isEqualTo("UP");
        }
        // Only health and info are exposed.
        assertThat(api.get("/actuator/env").status()).isEqualTo(404);
    }

    @Test
    void validIncomingRequestIdIsEchoedOtherwiseGenerated() {
        Res echoed = api.get("/api/skills", "X-Request-Id", "qa-req-123");
        assertThat(echoed.status()).isEqualTo(200);
        assertThat(echoed.header("X-Request-Id")).isEqualTo("qa-req-123");

        Res generated = api.get("/api/skills");
        assertThat(UUID.fromString(generated.header("X-Request-Id"))).isNotNull();

        for (String bad : List.of("has space", "semi;colon", "x".repeat(65), "under_score")) {
            Res r = api.get("/api/skills", "X-Request-Id", bad);
            assertThat(r.header("X-Request-Id")).as(bad).isNotEqualTo(bad);
            assertThat(UUID.fromString(r.header("X-Request-Id"))).isNotNull();
        }
        Res max = api.get("/api/skills", "X-Request-Id", "A".repeat(64));
        assertThat(max.header("X-Request-Id")).isEqualTo("A".repeat(64));

        // The same id is used in error bodies.
        Res err = api.get("/api/jobs/" + UUID.randomUUID(), "X-Request-Id", "qa-err-1");
        assertThat(err.status()).isEqualTo(404);
        assertThat(err.header("X-Request-Id")).isEqualTo("qa-err-1");
        JsonNode body = err.json();
        assertThat(body.get("requestId").asText()).isEqualTo("qa-err-1");
    }
}
