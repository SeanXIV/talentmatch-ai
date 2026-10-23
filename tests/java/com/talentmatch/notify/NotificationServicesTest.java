package com.talentmatch.notify;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.talentmatch.notify.NotificationSettingsRepository.Settings;
import com.talentmatch.notify.NotificationSettingsService.SettingsInput;
import com.talentmatch.notify.SmtpFailure.Kind;
import com.talentmatch.notify.SmtpFailure.Reason;
import com.talentmatch.service.exception.NotificationDeliveryException;
import com.talentmatch.service.exception.NotificationNotConfiguredException;
import com.talentmatch.service.exception.NotificationTestRateLimitedException;
import com.talentmatch.service.exception.RequestValidationException;
import com.talentmatch.support.MutableClock;
import com.talentmatch.web.error.FieldErrorDto;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

/** §5.4: settings validation matrix (incl. enabled-while-unconfigured) and the test send (D11, D12). */
class NotificationServicesTest {

    private final NotificationSettingsRepository repo = mock(NotificationSettingsRepository.class);
    private final NotificationDispatcher dispatcher = mock(NotificationDispatcher.class);
    private final EmailNotifier email = mock(EmailNotifier.class);
    private final MutableClock clock = MutableClock.fixedAt(Instant.parse("2026-10-11T10:00:00Z"));

    @BeforeEach
    void setUp() {
        when(email.channel()).thenReturn(Channel.EMAIL);
        when(email.configured()).thenReturn(true);
        when(email.info()).thenReturn(new EmailNotifier.EmailInfo(true, "smtp.example.com", 587, "STARTTLS",
                "a•••@example.com", "o•••@example.com", true, null));
        when(email.host()).thenReturn("smtp.example.com");
        when(email.port()).thenReturn(587);
        when(email.maskedTo()).thenReturn("o•••@example.com");
        when(email.rfcMessageId(any())).thenAnswer(i -> "<" + i.getArgument(0) + "@example.com>");
        when(repo.find()).thenReturn(Settings.defaults());
        when(repo.save(any())).thenAnswer(i -> i.getArgument(0));
    }

    private NotificationSettingsService settingsService() {
        DefaultListableBeanFactory f = new DefaultListableBeanFactory();
        f.registerSingleton("email", email);
        return new NotificationSettingsService(repo, new NotificationChannels(f.getBeanProvider(Notifier.class)), email,
                dispatcher);
    }

    private NotificationTestService testService() {
        return new NotificationTestService(email, repo, dispatcher, NotifyProperties.defaults(), clock);
    }

    private static Map<String, String> errors(Runnable r) {
        try {
            r.run();
        } catch (RequestValidationException e) {
            return e.getFieldErrors().stream().collect(Collectors.toMap(FieldErrorDto::field, FieldErrorDto::message));
        }
        throw new AssertionError("no RequestValidationException");
    }

    // ------------------------------------------------------------------ settings

    @Test
    void validSaveUpsertsAndWakesTheDispatcher() {
        var view = settingsService().save(new SettingsInput(true, 0.75, 10, null));
        assertThat(view.enabled()).isTrue();
        assertThat(view.channel()).isEqualTo(Channel.EMAIL);
        assertThat(view.minScore()).isEqualTo(0.75);
        assertThat(view.maxPerHour()).isEqualTo(10);
        assertThat(view.email().to()).isEqualTo("o•••@example.com");
        verify(repo).save(new Settings(true, Channel.EMAIL, 0.75, 10));
        verify(dispatcher).wake();
    }

    @Test
    void edgesAreAccepted() {
        settingsService().save(new SettingsInput(false, 0.0, 1, Channel.EMAIL));
        settingsService().save(new SettingsInput(false, 1.0, 200, Channel.EMAIL));
        verify(repo, times(2)).save(any());
    }

    @Test
    void validationMatrix() {
        NotificationSettingsService s = settingsService();
        assertThat(errors(() -> s.save(new SettingsInput(null, null, null, null)))).containsOnly(
                Map.entry("enabled", "enabled is required."),
                Map.entry("minScore", "minScore must be a number between 0 and 1."),
                Map.entry("maxPerHour", "maxPerHour must be a whole number between 1 and 200."));
        assertThat(errors(() -> s.save(null))).containsOnlyKeys("enabled", "minScore", "maxPerHour");
        for (Double bad : List.of(-0.01, 1.01, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertThat(errors(() -> s.save(new SettingsInput(false, bad, 5, null)))).as("%s", bad)
                    .containsOnlyKeys("minScore");
        }
        for (Integer bad : List.of(0, -1, 201)) {
            assertThat(errors(() -> s.save(new SettingsInput(false, 0.5, bad, null)))).as("%s", bad)
                    .containsOnlyKeys("maxPerHour");
        }
        verify(repo, never()).save(any());
        verify(dispatcher, never()).wake();
    }

    @Test
    void enablingWhileUnconfiguredIsRefusedOnEnabled() {
        when(email.configured()).thenReturn(false);
        assertThat(errors(() -> settingsService().save(new SettingsInput(true, 0.6, 20, null))))
                .containsOnly(Map.entry("enabled", "Email isn't configured: set SMTP_HOST, NOTIFY_EMAIL_FROM and "
                        + "NOTIFY_EMAIL_TO (plus SMTP_USERNAME and SMTP_PASSWORD if your server needs a login), "
                        + "then restart."));
        // disabling is always allowed
        settingsService().save(new SettingsInput(false, 0.6, 20, null));
        verify(repo).save(any());
    }

    // ------------------------------------------------------------------ test send

    @Test
    void notConfiguredIs409AndDoesNotUseTheSlot() throws Exception {
        when(email.configured()).thenReturn(false);
        NotificationTestService t = testService();
        assertThatThrownBy(t::sendTest).isInstanceOf(NotificationNotConfiguredException.class);
        when(email.configured()).thenReturn(true);
        assertThat(t.sendTest().delivered()).isTrue();          // the 409 took no slot
    }

    @Test
    void successResumesAndWakesTheDispatcher() throws Exception {
        var r = testService().sendTest();
        assertThat(r.channel()).isEqualTo(Channel.EMAIL);
        assertThat(r.to()).isEqualTo("o•••@example.com");
        assertThat(r.messageId()).matches("<test-[0-9a-f-]{36}@example.com>");
        verify(dispatcher).resume();
        verify(dispatcher).wake();
        verify(email).send(any());
    }

    @Test
    void rateLimitCountsFailedAttemptsToo() throws Exception {
        NotificationTestService t = testService();
        doThrow(new NotifyException(new SmtpFailure(Kind.CONFIG, 535, Reason.AUTH_FAILED, "x")))
                .when(email).send(any());
        assertThatThrownBy(t::sendTest).isInstanceOf(NotificationDeliveryException.class)
                .hasMessage("The mail server rejected the login (535). Check SMTP_USERNAME and SMTP_PASSWORD.");
        verify(dispatcher, never()).resume();

        clock.advance(Duration.ofSeconds(12));
        assertThatThrownBy(t::sendTest).isInstanceOfSatisfying(NotificationTestRateLimitedException.class, e -> {
            assertThat(e.getRetryAfterSeconds()).isEqualTo(18);
            assertThat(e.getMessage()).isEqualTo("A test email was sent less than 30 seconds ago. "
                    + "Try again in 18 seconds.");
        });
        verify(email, times(1)).send(any());

        clock.advance(Duration.ofSeconds(18));
        assertThatThrownBy(t::sendTest).isInstanceOf(NotificationDeliveryException.class);
        verify(email, times(2)).send(any());
    }

    @Test
    void retryAfterIsAtLeastOneSecond() {
        NotificationTestService t = testService();
        t.sendTest();
        clock.advance(Duration.ofMillis(29_999));
        assertThatThrownBy(t::sendTest).isInstanceOfSatisfying(NotificationTestRateLimitedException.class,
                e -> assertThat(e.getRetryAfterSeconds()).isOne());
    }

    @Test
    void deliveryMessageNamesHostAndPort() throws Exception {
        doThrow(new NotifyException(new SmtpFailure(Kind.TRANSIENT, null, Reason.CONNECT_REFUSED, "x")))
                .when(email).send(any());
        assertThatThrownBy(testService()::sendTest).isInstanceOf(NotificationDeliveryException.class)
                .hasMessage("Couldn't connect to smtp.example.com:587: the connection was refused. "
                        + "Check SMTP_HOST and SMTP_PORT.");
    }
}
