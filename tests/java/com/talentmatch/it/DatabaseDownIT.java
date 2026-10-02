package com.talentmatch.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.talentmatch.support.AbstractApiIT;
import com.talentmatch.support.Api;
import com.talentmatch.support.Api.Res;
import com.talentmatch.support.TestcontainersConfiguration;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * §8.12 Database stopped mid-flight: 503 DATABASE_UNAVAILABLE + Retry-After, health DOWN. Own container.
 *
 * <p>Covers both outage paths regardless of local timing:
 * <ul>
 *   <li>dead pooled connection: a request is parked on a table lock (held by a separate raw JDBC
 *       connection) while already using a pooled connection inside a transaction, then the
 *       container is killed. The query fails with an I/O error and the rollback fails on Hikari's
 *       closed-connection proxy (the CI failure: JpaSystemException "Unable to rollback").</li>
 *   <li>connection acquisition: subsequent requests cannot get a connection at all.</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(DatabaseDownIT.OwnContainer.class)
@DirtiesContext
@ExtendWith(OutputCaptureExtension.class)
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
    void databaseOutageMapsTo503(CapturedOutput output) throws Exception {
        Api api = new Api(port, mapper);
        UUID job = jdbc.queryForObject("INSERT INTO job (title, company) VALUES ('T', 'C') RETURNING id", UUID.class);
        // warm the pool
        for (int i = 0; i < 3; i++) {
            assertThat(api.get("/api/jobs").status()).isEqualTo(200);
        }

        // Dead-pooled-connection path: park GET /api/jobs on a lock so its pooled connection is
        // mid-transaction when the database dies.
        CompletableFuture<Res> inFlight;
        Connection locker = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(),
                postgres.getPassword());
        try {
            locker.setAutoCommit(false);
            try (Statement st = locker.createStatement()) {
                st.execute("LOCK TABLE job IN ACCESS EXCLUSIVE MODE");
            }
            inFlight = CompletableFuture.supplyAsync(() -> api.get("/api/jobs"));
            awaitLockWaiter(locker);
            postgres.stop();
        } finally {
            try {
                locker.close();
            } catch (SQLException ignored) {
                // connection died with the container
            }
        }
        Res parked = inFlight.get(90, TimeUnit.SECONDS);
        assertThat(parked.status()).as("in-flight request on a dead pooled connection: " + parked).isEqualTo(503);
        assertThat(parked.json().get("code").asText()).isEqualTo("DATABASE_UNAVAILABLE");
        assertThat(parked.header("Retry-After")).isEqualTo("5");
        for (String leak : AbstractApiIT.LEAKS) {
            assertThat(parked.body()).doesNotContain(leak);
        }

        // Connection-acquisition path.

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

        assertThat(output.getAll()).as("no outage request may reach the 500 handler")
                .doesNotContain("Unexpected error on");
    }

    /** Waits until another backend is blocked on a lock (the parked API request). */
    private static void awaitLockWaiter(Connection locker) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (System.nanoTime() < deadline) {
            try (Statement st = locker.createStatement();
                 ResultSet rs = st.executeQuery("SELECT count(*) FROM pg_stat_activity "
                         + "WHERE wait_event_type = 'Lock' AND pid <> pg_backend_pid()")) {
                rs.next();
                if (rs.getInt(1) > 0) {
                    return;
                }
            }
            Thread.sleep(50);
        }
        throw new AssertionError("API request never blocked on the table lock");
    }
}
