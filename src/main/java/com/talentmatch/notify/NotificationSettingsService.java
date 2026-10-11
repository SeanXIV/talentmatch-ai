package com.talentmatch.notify;

import com.talentmatch.service.exception.NotificationNotConfiguredException;
import com.talentmatch.service.exception.RequestValidationException;
import com.talentmatch.web.error.FieldErrorDto;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * {@code GET} and {@code PUT /api/notifications/settings} (§5.4). The settings row is one upsert
 * statement (no transaction needed); after a save the dispatcher is woken, so turning notifications on
 * sends what is already due.
 */
@Service
public class NotificationSettingsService {

    static final String ENABLED_REQUIRED = "enabled is required.";
    static final String MIN_SCORE_MESSAGE = "minScore must be a number between 0 and 1.";
    static final String MAX_PER_HOUR_MESSAGE = "maxPerHour must be a whole number between 1 and 200.";
    static final int MAX_PER_HOUR_LIMIT = 200;

    private final NotificationSettingsRepository settings;
    private final NotificationChannels channels;
    private final EmailNotifier email;
    private final NotificationDispatcher dispatcher;

    public NotificationSettingsService(NotificationSettingsRepository settings, NotificationChannels channels,
                                       EmailNotifier email, NotificationDispatcher dispatcher) {
        this.settings = settings;
        this.channels = channels;
        this.email = email;
        this.dispatcher = dispatcher;
    }

    /** The full replacement of the settings ({@code channel} optional: EMAIL). */
    public record SettingsInput(Boolean enabled, Double minScore, Integer maxPerHour, Channel channel) {
    }

    /** The settings with what may be shown about the channel (no username, no password, masked addresses). */
    public record SettingsView(boolean enabled, Channel channel, double minScore, int maxPerHour,
                               EmailNotifier.EmailInfo email) {
    }

    public SettingsView get() {
        return view(settings.find());
    }

    /**
     * Validates and stores the settings.
     *
     * @throws RequestValidationException 400 VALIDATION_FAILED with one entry per invalid field; also
     *                                    {@code enabled: true} while the channel isn't configured
     */
    public SettingsView save(SettingsInput in) {
        SettingsInput input = in == null ? new SettingsInput(null, null, null, null) : in;
        Channel channel = input.channel() == null ? Channel.EMAIL : input.channel();
        List<FieldErrorDto> errors = new ArrayList<>();
        if (input.enabled() == null) {
            errors.add(new FieldErrorDto("enabled", ENABLED_REQUIRED));
        } else if (input.enabled() && !channels.configured(channel)) {
            errors.add(new FieldErrorDto("enabled", NotificationNotConfiguredException.MESSAGE));
        }
        Double minScore = input.minScore();
        if (minScore == null || minScore.isNaN() || minScore.isInfinite() || minScore < 0 || minScore > 1) {
            errors.add(new FieldErrorDto("minScore", MIN_SCORE_MESSAGE));
        }
        Integer maxPerHour = input.maxPerHour();
        if (maxPerHour == null || maxPerHour < 1 || maxPerHour > MAX_PER_HOUR_LIMIT) {
            errors.add(new FieldErrorDto("maxPerHour", MAX_PER_HOUR_MESSAGE));
        }
        if (!errors.isEmpty()) {
            throw new RequestValidationException(errors);
        }
        NotificationSettingsRepository.Settings saved = settings.save(new NotificationSettingsRepository.Settings(
                input.enabled(), channel, minScore, maxPerHour));
        dispatcher.wake();
        return view(saved);
    }

    private SettingsView view(NotificationSettingsRepository.Settings s) {
        return new SettingsView(s.enabled(), s.channel(), s.minScore(), s.maxPerHour(), email.info());
    }
}
