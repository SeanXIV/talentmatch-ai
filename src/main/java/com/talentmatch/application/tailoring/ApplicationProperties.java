package com.talentmatch.application.tailoring;

import java.time.DateTimeException;
import java.time.ZoneId;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Tailored applications and the tracker ({@code talentmatch.applications.*}, Phase 7 §7). Lives in
 * {@code application.tailoring} so {@code ai.config} can read it without importing the root
 * {@code application} package (§1.1).
 *
 * <p>Step 1 binds the tracker's setting only; the generation and cover-letter settings arrive with
 * the tailoring engine (step 4).
 *
 * @param zone time zone of the {@code appliedOn} default and the cover-letter date (default
 *             Africa/Johannesburg)
 */
@Validated
@ConfigurationProperties("talentmatch.applications")
public record ApplicationProperties(@DefaultValue("Africa/Johannesburg") String zone) {

    public static final String DEFAULT_ZONE = "Africa/Johannesburg";

    public ApplicationProperties {
        zone = zone == null || zone.isBlank() ? DEFAULT_ZONE : zone.strip();
        try {
            ZoneId.of(zone);
        } catch (DateTimeException e) {
            throw new IllegalArgumentException("talentmatch.applications.zone is " + zone
                    + " but must be a time zone id such as Africa/Johannesburg or UTC");
        }
    }

    /** All defaults. */
    public static ApplicationProperties defaults() {
        return new ApplicationProperties(null);
    }

    public ZoneId zoneId() {
        return ZoneId.of(zone);
    }
}
