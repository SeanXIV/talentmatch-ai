package com.talentmatch.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.talentmatch.TalentMatchApplication;
import java.net.ServerSocket;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/** Startup without a reachable database prints the actionable failure analysis (no Docker needed). */
@ExtendWith(OutputCaptureExtension.class)
class DatabaseStartupFailureIT {

    @Test
    void unreachableDatabaseGivesActionableMessage(CapturedOutput output) throws Exception {
        int port;
        try (ServerSocket s = new ServerSocket(0)) {
            port = s.getLocalPort();
        }
        int closedPort = port;
        assertThatThrownBy(() -> new SpringApplicationBuilder(TalentMatchApplication.class).run(
                "--server.port=0",
                "--spring.datasource.url=jdbc:postgresql://localhost:" + closedPort + "/tmqa",
                "--spring.datasource.username=qa_user",
                "--spring.datasource.password=qa-secret-pw-123"))
                .isInstanceOf(Exception.class);
        assertThat(output.getAll())
                .contains("APPLICATION FAILED TO START")
                .contains("Could not connect to PostgreSQL at localhost:" + closedPort + "/tmqa as qa_user.")
                .contains("Start it with `bash scripts/start_db.sh`, or set DB_HOST/DB_PORT/DB_NAME/DB_USER/DB_PASSWORD.")
                .doesNotContain("qa-secret-pw-123");
    }
}
