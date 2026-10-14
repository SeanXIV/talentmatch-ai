package com.talentmatch.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.talentmatch.TalentMatchApplication;
import com.talentmatch.support.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * §4.9: an invalid SMTP setup fails startup with the analyzer's message, which names the variable to
 * fix and prints no configured value (no username, password or address), through the real Boot
 * failure-analysis path.
 */
@ExtendWith(OutputCaptureExtension.class)
class NotifyStartupFailureIT {

    private static final String USER = "zq-startup-login-99";
    private static final String TO = "zq-startup-owner@example.org";

    private static void run(String... extra) {
        String[] base = {"--server.port=0", "--talentmatch.ai.enabled=false", "--talentmatch.feed.scheduler.enabled=false",
                "--talentmatch.notify.email.host=smtp.example.com",
                "--talentmatch.notify.email.from=alerts@example.com", "--talentmatch.notify.email.to=" + TO};
        String[] args = new String[base.length + extra.length];
        System.arraycopy(base, 0, args, 0, base.length);
        System.arraycopy(extra, 0, args, base.length, extra.length);
        SpringApplication.from(TalentMatchApplication::main).with(TestcontainersConfiguration.class).run(args);
    }

    @Test
    void usernameWithoutPasswordGivesAnActionableMessageWithoutValues(CapturedOutput output) {
        assertThatThrownBy(() -> run("--talentmatch.notify.email.username=" + USER)).isInstanceOf(Exception.class);
        assertThat(output.getAll())
                .contains("APPLICATION FAILED TO START")
                .contains("SMTP_USERNAME is set but SMTP_PASSWORD is empty.")
                .contains("Set both, or neither if your server needs no login.")
                .doesNotContain(USER)
                .doesNotContain(TO);
    }

    @Test
    void invalidRecipientNamesTheVariableOnly(CapturedOutput output) {
        assertThatThrownBy(() -> run("--talentmatch.notify.email.to=zq-no-domain-owner"))
                .isInstanceOf(Exception.class);
        assertThat(output.getAll())
                .contains("APPLICATION FAILED TO START")
                .contains("NOTIFY_EMAIL_TO is not a single valid email address.")
                .doesNotContain("zq-no-domain-owner");
    }
}
