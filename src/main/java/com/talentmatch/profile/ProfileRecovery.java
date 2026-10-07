package com.talentmatch.profile;

import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * On startup, CVs that were RUNNING when the app stopped go back to PENDING, and every PENDING CV
 * is queued again, so a restart never leaves a CV stuck. Assumes a single app instance
 * (PRODUCTION_READINESS: in-process extraction queue).
 */
@Component
public class ProfileRecovery {

    private static final Logger log = LoggerFactory.getLogger(ProfileRecovery.class);

    private final ResumeRepository repository;
    private final ResumeExtractionService extraction;

    public ProfileRecovery(ResumeRepository repository, ResumeExtractionService extraction) {
        this.repository = repository;
        this.extraction = extraction;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void requeueUnfinished() {
        try {
            int reset = repository.resetRunning();
            List<UUID> pending = repository.findPendingIds();
            if (reset > 0 || !pending.isEmpty()) {
                log.info("Re-queuing {} unfinished CV extraction(s) ({} were interrupted)", pending.size(), reset);
            }
            pending.forEach(extraction::enqueue);
        } catch (RuntimeException e) {
            log.warn("Could not re-queue unfinished CV extractions ({})", ResumeExtractionService.describe(e));
        }
    }
}
