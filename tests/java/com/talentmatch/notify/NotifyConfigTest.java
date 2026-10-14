package com.talentmatch.notify;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.diagnostics.FailureAnalysis;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mail.javamail.JavaMailSender;

/**
 * §9.1 #14 config validation with {@link ApplicationContextRunner}: {@link NotifyConfig} with the real
 * {@code application.yml} (so {@code ${SMTP_*}} placeholders resolve as in production; an env var is
 * simulated with a property of the same name).
 */
@ExtendWith(OutputCaptureExtension.class)
class NotifyConfigTest {

    private static final String SECRET_USER = "zq-smtp-login-4711";
    private static final String SECRET_PW = "Zq-Smtp-Secret-4711";
    private static final String SECRET_TO = "zq-private-owner@example.org";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withBean(Clock.class, Clock::systemUTC)
            .withUserConfiguration(NotifyConfig.class);

    private static String fullTrace(Throwable t) {
        StringWriter w = new StringWriter();
        t.printStackTrace(new PrintWriter(w));
        return w.toString();
    }

    private static NotifyConfigurationException notifyCause(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c instanceof NotifyConfigurationException n) {
                return n;
            }
        }
        throw new AssertionError("no NotifyConfigurationException in " + t);
    }

    @Test
    void blankHostMeansNotConfiguredOneInfoLineAndNoSender(CapturedOutput output) {
        runner.run(ctx -> {
            assertThat(ctx).hasNotFailed();
            EmailNotifier n = ctx.getBean(EmailNotifier.class);
            assertThat(n.configured()).isFalse();
            assertThat(ctx.getBeansOfType(JavaMailSender.class)).isEmpty();
            assertThat(ctx.getBean(NotifyProperties.class).email().port()).isEqualTo(587);
        });
        assertThat(output.getAll().lines()
                .filter(l -> l.contains("Email notifications are off: SMTP is not configured (set SMTP_HOST, "
                        + "NOTIFY_EMAIL_FROM and NOTIFY_EMAIL_TO).")).count()).isOne();
        assertThat(output.getAll()).doesNotContain("Email notifications ready");
    }

    @Test
    void emptyPortAndSecurityEnvVarsFallBackToDefaults() {
        runner.withPropertyValues("SMTP_HOST=smtp.example.com", "SMTP_PORT=", "SMTP_SECURITY=",
                        "NOTIFY_EMAIL_FROM=alerts@example.com", "NOTIFY_EMAIL_TO=owner@example.com",
                        "SMTP_USERNAME=", "SMTP_PASSWORD=")
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    NotifyProperties.Email e = ctx.getBean(NotifyProperties.class).email();
                    assertThat(e.port()).isEqualTo(587);
                    assertThat(e.security()).isEqualTo(NotifyProperties.Security.STARTTLS);
                    assertThat(e.username()).isNull();
                    assertThat(e.password()).isNull();
                    assertThat(ctx.getBean(EmailNotifier.class).configured()).isTrue();
                    assertThat(ctx.getBeansOfType(JavaMailSender.class)).isEmpty();
                });
    }

    @Test
    void envVarsBindThroughTheYml(CapturedOutput output) {
        runner.withPropertyValues("SMTP_HOST=smtp.example.com", "SMTP_PORT=465", "SMTP_SECURITY=SSL",
                        "NOTIFY_EMAIL_FROM=alerts@example.com", "NOTIFY_EMAIL_TO=" + SECRET_TO,
                        "SMTP_USERNAME=" + SECRET_USER, "SMTP_PASSWORD=" + SECRET_PW)
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    NotifyProperties.Email e = ctx.getBean(NotifyProperties.class).email();
                    assertThat(e.port()).isEqualTo(465);
                    assertThat(e.security()).isEqualTo(NotifyProperties.Security.SSL);
                    assertThat(e.password()).isEqualTo(SECRET_PW);
                });
        assertThat(output.getAll()).contains("Email notifications ready: host=smtp.example.com port=465 security=SSL")
                .doesNotContain(SECRET_USER).doesNotContain(SECRET_PW).doesNotContain(SECRET_TO)
                .doesNotContain("alerts@example.com");
    }

    @Test
    void partialConfigurationIsNotAFailure(CapturedOutput output) {
        runner.withPropertyValues("SMTP_HOST=smtp.example.com").run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx.getBean(EmailNotifier.class).configured()).isFalse();
        });
        assertThat(output.getAll()).contains("Missing: NOTIFY_EMAIL_FROM, NOTIFY_EMAIL_TO.");
    }

    @Test
    void usernameWithoutPasswordFailsWithAnAnalyzedMessageAndNoValues() {
        runner.withPropertyValues("SMTP_USERNAME=" + SECRET_USER, "SMTP_HOST=smtp.example.com",
                "NOTIFY_EMAIL_FROM=alerts@example.com", "NOTIFY_EMAIL_TO=" + SECRET_TO).run(ctx -> {
            assertThat(ctx).hasFailed();
            Throwable failure = ctx.getStartupFailure();
            FailureAnalysis analysis = new NotifyStartupFailureAnalyzer().analyze(failure);
            assertThat(analysis).isNotNull();
            assertThat(analysis.getDescription()).isEqualTo("SMTP_USERNAME is set but SMTP_PASSWORD is empty.");
            assertThat(analysis.getAction()).isEqualTo("Set both, or neither if your server needs no login.");
            assertThat(fullTrace(failure)).doesNotContain(SECRET_USER).doesNotContain(SECRET_TO);
        });
    }

    @Test
    void passwordWithoutUsernameFails() {
        runner.withPropertyValues("SMTP_PASSWORD=" + SECRET_PW).run(ctx -> {
            assertThat(ctx).hasFailed();
            assertThat(notifyCause(ctx.getStartupFailure()).getDescription()).contains("SMTP_USERNAME");
            assertThat(fullTrace(ctx.getStartupFailure())).doesNotContain(SECRET_PW);
        });
    }

    @Test
    void invalidToFails() {
        runner.withPropertyValues("SMTP_HOST=smtp.example.com", "NOTIFY_EMAIL_FROM=alerts@example.com",
                "NOTIFY_EMAIL_TO=zq-owner-without-domain").run(ctx -> {
            assertThat(ctx).hasFailed();
            NotifyConfigurationException e = notifyCause(ctx.getStartupFailure());
            assertThat(e.getDescription()).isEqualTo("NOTIFY_EMAIL_TO is not a single valid email address.");
            assertThat(fullTrace(ctx.getStartupFailure())).doesNotContain("zq-owner-without-domain");
        });
    }

    @Test
    void noneWithARemoteHostFails() {
        runner.withPropertyValues("SMTP_HOST=smtp.example.com", "SMTP_SECURITY=NONE",
                "NOTIFY_EMAIL_FROM=alerts@example.com", "NOTIFY_EMAIL_TO=owner@example.com").run(ctx -> {
            assertThat(ctx).hasFailed();
            assertThat(notifyCause(ctx.getStartupFailure()).getDescription()).contains("SMTP_SECURITY=NONE");
        });
        runner.withPropertyValues("SMTP_HOST=127.0.0.1", "SMTP_SECURITY=NONE", "SMTP_PORT=2525",
                "NOTIFY_EMAIL_FROM=alerts@example.com", "NOTIFY_EMAIL_TO=owner@example.com")
                .run(ctx -> assertThat(ctx).hasNotFailed());
    }

    @Test
    void dispatchExecutorIsOneThreadWithAOneSlotQueue() {
        runner.run(ctx -> {
            var ex = ctx.getBean(NotifyConfig.DISPATCH_EXECUTOR,
                    org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor.class);
            assertThat(ex.getCorePoolSize()).isOne();
            assertThat(ex.getMaxPoolSize()).isOne();
            assertThat(ex.getQueueCapacity()).isOne();
            assertThat(ex.getThreadNamePrefix()).isEqualTo("notify-");
        });
    }
}
