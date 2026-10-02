package com.talentmatch.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.talentmatch.support.AbstractApiIT;
import com.talentmatch.support.Api;
import com.talentmatch.support.Api.Res;
import com.talentmatch.support.TestcontainersConfiguration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.testcontainers.containers.PostgreSQLContainer;

/** §8.12 Database stopped mid-flight: 503 DATABASE_UNAVAILABLE + Retry-After, health DOWN. Own container. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(DatabaseDownIT.OwnContainer.class)
@DirtiesContext
class DatabaseDownIT {

    @TestConfiguration(proxyBeanMethods = false)
    static class OwnContainer {
        @Bean
        @ServiceConnection
        PostgreSQLContainer<?> disposablePostgres() {
            return new PostgreSQLContainer<>(TestcontainersConfiguration.POSTGRES_IMAGE);
        }
    }

    @LocalServerPort
    int port;

    @Autowired
    PostgreSQLContainer<?> postgres;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ObjectMapper mapper;

    @Test
    void databaseOutageMapsTo503() {
        Api api = new Api(port, mapper);
        UUID job = jdbc.queryForObject("INSERT INTO job (title, company) VALUES ('T', 'C') RETURNING id", UUID.class);
        assertThat(api.get("/api/jobs").status()).isEqualTo(200);

        postgres.stop();

        for (String path : new String[] {"/api/jobs", "/api/candidates/" + UUID.randomUUID(), "/api/jobs/" + job + "/matches"}) {
            Res r = api.get(path);
            assertThat(r.status()).as(path + " " + r).isEqualTo(503);
            JsonNode b = r.json();
            assertThat(b.get("code").asText()).isEqualTo("DATABASE_UNAVAILABLE");
            assertThat(b.get("message").asText())
                    .isEqualTo("The database is temporarily unavailable. Please try again in a moment.");
            assertThat(r.header("Retry-After")).isEqualTo("5");
            assertThat(b.get("requestId").asText()).isEqualTo(r.header("X-Request-Id"));
            for (String leak : AbstractApiIT.LEAKS) {
                assertThat(r.body()).doesNotContain(leak);
            }
        }
        Res post = api.post("/api/skills", "{\"name\":\"Go\"}");
        assertThat(post.status()).as(post.toString()).isEqualTo(503);

        Res health = api.get("/actuator/health");
        assertThat(health.status()).isEqualTo(503);
        assertThat(health.json().get("status").asText()).isEqualTo("DOWN");
    }
}
