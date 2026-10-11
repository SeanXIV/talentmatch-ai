package com.talentmatch.notify;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.talentmatch.notify.NotifyProperties.Email;
import com.talentmatch.notify.NotifyProperties.Security;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** §7 / §9.1 #15: ranges, normalization, masking in toString, password bound only to ${SMTP_PASSWORD:}. */
class NotifyPropertiesTest {

    private static NotifyProperties props(int maxAttempts, Duration retryInitial, Duration retryMax,
                                          Duration pause, Duration maxAge, Duration testInterval) {
        return new NotifyProperties(null, null, maxAttempts, retryInitial, retryMax, pause, maxAge, testInterval,
                Email.unconfigured());
    }

    @Test
    void defaults() {
        NotifyProperties p = NotifyProperties.defaults();
        assertThat(p.zone()).isEqualTo("Africa/Johannesburg");
        assertThat(p.appBaseUrl()).isEqualTo("http://127.0.0.1:8080");
        assertThat(p.maxAttempts()).isEqualTo(5);
        assertThat(p.retryInitial()).isEqualTo(Duration.ofMinutes(1));
        assertThat(p.retryMax()).isEqualTo(Duration.ofMinutes(15));
        assertThat(p.configErrorPause()).isEqualTo(Duration.ofMinutes(15));
        assertThat(p.maxAge()).isEqualTo(Duration.ofHours(24));
        assertThat(p.testInterval()).isEqualTo(Duration.ofSeconds(30));
        Email e = p.email();
        assertThat(e.port()).isEqualTo(587);
        assertThat(e.security()).isEqualTo(Security.STARTTLS);
        assertThat(e.connectionTimeout()).isEqualTo(Duration.ofSeconds(10));
        assertThat(e.configured()).isFalse();
    }

    @Test
    void ranges() {
        Duration m = Duration.ofMinutes(1);
        assertThatThrownBy(() -> props(0, m, m, m, Duration.ofHours(1), Duration.ofSeconds(1)))
                .hasMessageContaining("max-attempts");
        assertThatThrownBy(() -> props(21, m, m, m, Duration.ofHours(1), Duration.ofSeconds(1)))
                .hasMessageContaining("max-attempts");
        assertThatThrownBy(() -> props(5, Duration.ofMillis(500), m, m, Duration.ofHours(1), Duration.ofSeconds(1)))
                .hasMessageContaining("retry-initial");
        assertThatThrownBy(() -> props(5, Duration.ofMinutes(2), m, m, Duration.ofHours(1), Duration.ofSeconds(1)))
                .as("retry-max below retry-initial").hasMessageContaining("retry-max");
        assertThatThrownBy(() -> props(5, m, Duration.ofHours(25), m, Duration.ofHours(1), Duration.ofSeconds(1)))
                .hasMessageContaining("retry-max");
        assertThatThrownBy(() -> props(5, m, m, Duration.ofSeconds(59), Duration.ofHours(1), Duration.ofSeconds(1)))
                .hasMessageContaining("config-error-pause");
        assertThatThrownBy(() -> props(5, m, m, m, Duration.ofMinutes(59), Duration.ofSeconds(1)))
                .hasMessageContaining("max-age");
        assertThatThrownBy(() -> props(5, m, m, m, Duration.ofDays(8), Duration.ofSeconds(1)))
                .hasMessageContaining("max-age");
        assertThatThrownBy(() -> props(5, m, m, m, Duration.ofHours(1), Duration.ofMinutes(11)))
                .hasMessageContaining("test-interval");
        // inclusive edges
        NotifyProperties edge = props(20, Duration.ofSeconds(1), Duration.ofHours(24), Duration.ofHours(24),
                Duration.ofDays(7), Duration.ofMinutes(10));
        assertThat(edge.maxAttempts()).isEqualTo(20);
        assertThat(props(1, Duration.ofHours(1), Duration.ofHours(1), m, Duration.ofHours(1), Duration.ofSeconds(1))
                .maxAttempts()).isOne();
    }

    @Test
    void zoneAndBaseUrl() {
        assertThatThrownBy(() -> new NotifyProperties("Mars/Olympus", null, 5, null, null, null, null, null, null))
                .hasMessageContaining("talentmatch.notify.zone");
        for (String bad : List.of("ftp://x.example", "127.0.0.1:8080", "/relative", "javascript:alert(1)")) {
            assertThatThrownBy(() -> new NotifyProperties(null, bad, 5, null, null, null, null, null, null))
                    .as(bad).hasMessageContaining("app-base-url");
        }
        assertThat(new NotifyProperties(" UTC ", "https://tm.example.com///", 5, null, null, null, null, null, null))
                .satisfies(p -> {
                    assertThat(p.zoneId().getId()).isEqualTo("UTC");
                    assertThat(p.appBaseUrl()).isEqualTo("https://tm.example.com");
                });
    }

    @Test
    void emailRangesAndNormalization() {
        assertThatThrownBy(() -> new Email(null, 0, null, null, null, null, null, null, null, null))
                .hasMessageContaining("port");
        assertThatThrownBy(() -> new Email(null, 65536, null, null, null, null, null, null, null, null))
                .hasMessageContaining("port");
        assertThatThrownBy(() -> new Email(null, 587, null, null, null, null, null, Duration.ofMillis(999), null, null))
                .hasMessageContaining("connection-timeout");
        assertThatThrownBy(() -> new Email(null, 587, null, null, null, null, null, null, Duration.ofSeconds(61), null))
                .hasMessageContaining("read-timeout");
        assertThatThrownBy(() -> new Email(null, 587, null, null, null, null, null, null, null, Duration.ZERO))
                .hasMessageContaining("write-timeout");
        Email e = new Email("  smtp.example.com ", null, null, " user ", "  pw with spaces  ", " a@example.com ",
                " b@example.com ", null, null, null);
        assertThat(e.host()).isEqualTo("smtp.example.com");
        assertThat(e.port()).isEqualTo(587);
        assertThat(e.security()).isEqualTo(Security.STARTTLS);
        assertThat(e.username()).isEqualTo("user");
        assertThat(e.password()).as("never stripped").isEqualTo("  pw with spaces  ");
        assertThat(e.from()).isEqualTo("a@example.com");
        assertThat(e.configured()).isTrue();
        assertThat(e.authenticated()).isTrue();
        assertThat(new Email(" ", null, null, " ", " ", " ", " ", null, null, null).configured()).isFalse();
    }

    @Test
    void toStringMasksSecretsAndAddresses() {
        Email e = new Email("smtp.example.com", 587, Security.STARTTLS, "zq-login-name", "Zq-Secret-Pw-55",
                "\"Zq Sender\" <zqsender@example.com>", "zqowner@example.com", null, null, null);
        String s = NotifyProperties.withEmail(e).toString();
        assertThat(s).doesNotContain("zq-login-name").doesNotContain("Zq-Secret-Pw-55")
                .doesNotContain("zqsender@").doesNotContain("zqowner@").doesNotContain("Zq Sender")
                .contains("smtp.example.com").contains("password=******").contains("username=******")
                .contains("to=z•••@example.com");
        assertThat(Email.unconfigured().toString()).contains("password=null");
    }

    /** §9.1 #15: every committed application*.yml binds the SMTP password only to ${SMTP_PASSWORD:}. */
    @Test
    void ymlBindsPasswordOnlyToTheEnvVar() throws IOException {
        Path resources = Path.of("src/main/resources");
        List<Path> ymls;
        try (Stream<Path> files = Files.list(resources)) {
            ymls = files.filter(f -> f.getFileName().toString().matches("application.*\\.ya?ml")).toList();
        }
        assertThat(ymls).isNotEmpty();
        Pattern passwordLine = Pattern.compile("^\\s*password:\\s*(\\S+)");
        boolean seen = false;
        for (Path yml : ymls) {
            List<String> lines = Files.readAllLines(yml);
            boolean inEmail = false;
            int emailIndent = -1;
            for (String line : lines) {
                int indent = line.length() - line.stripLeading().length();
                if (line.strip().startsWith("email:")) {
                    inEmail = true;
                    emailIndent = indent;
                    continue;
                }
                if (inEmail && !line.isBlank() && indent <= emailIndent) {
                    inEmail = false;
                }
                Matcher m = passwordLine.matcher(line);
                if (inEmail && m.find()) {
                    seen = true;
                    assertThat(m.group(1)).as("%s: %s", yml, line).isEqualTo("${SMTP_PASSWORD:}");
                }
                assertThat(line).as("%s: no notify.email.password literal", yml)
                        .doesNotContainPattern("notify\\.email\\.password\\s*[:=]\\s*[^$\\s]");
            }
        }
        assertThat(seen).as("talentmatch.notify.email.password present in application.yml").isTrue();
    }
}
