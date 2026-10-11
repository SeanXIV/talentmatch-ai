package com.talentmatch.preferences;

import com.talentmatch.service.exception.NotFoundException;
import java.util.Optional;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The owner's hand-entered job preferences. Nothing here (or anywhere) derives preferences from
 * the CV or an AI answer: {@link PreferencesValidator} on {@code PUT /api/preferences} is the only
 * way in. A save publishes {@link PreferencesSavedEvent} in its transaction.
 */
@Service
public class PreferencesService {

    private final PreferencesRepository repository;
    private final ApplicationEventPublisher events;

    public PreferencesService(PreferencesRepository repository, ApplicationEventPublisher events) {
        this.repository = repository;
        this.events = events;
    }

    /** The saved preferences; 404 PREFERENCES_NOT_FOUND when none were saved. */
    @Transactional(readOnly = true)
    public PreferencesRepository.Stored get() {
        return repository.find().orElseThrow(NotFoundException::preferences);
    }

    /** The saved preferences, if any (for the feed; empty = no filters). */
    @Transactional(readOnly = true)
    public Optional<PreferencesRepository.Stored> find() {
        return repository.find();
    }

    /** Full replace; the version goes up by one on every save. */
    @Transactional
    public PreferencesRepository.Stored save(PreferencesInput input) {
        JobPreferences preferences = PreferencesValidator.validate(input);
        PreferencesRepository.Stored stored = repository.save(preferences);
        events.publishEvent(new PreferencesSavedEvent(stored.version()));
        return stored;
    }
}
