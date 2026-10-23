package com.talentmatch.application;

import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * On startup, generations that were RUNNING when the app stopped go back to PENDING, and every PENDING
 * generation is queued again, so a restart never leaves one stuck. Each claim counts an attempt; after
 * {@link GenerationRepository#MAX_ATTEMPTS} the generation is FAILED/TOO_MANY_ATTEMPTS. Assumes a single
 * app instance (PRODUCTION_READINESS: single-instance generation queue).
 */
@Component
public class GenerationRecovery {

    private static final Logger log = LoggerFactory.getLogger(GenerationRecovery.class);

    private final GenerationRepository repository;
    private final GenerationService generations;

    public GenerationRecovery(GenerationRepository repository, GenerationService generations) {
        this.repository = repository;
        this.generations = generations;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void requeueUnfinished() {
        try {
            int reset = repository.resetRunning();
            List<UUID> pending = repository.findPendingIds();
            if (reset > 0 || !pending.isEmpty()) {
                log.info("Re-queuing {} unfinished generation(s) ({} were interrupted)", pending.size(), reset);
            }
            pending.forEach(generations::enqueue);
        } catch (RuntimeException e) {
            log.warn("Could not re-queue unfinished generations ({})", GenerationService.describe(e));
        }
    }
}
