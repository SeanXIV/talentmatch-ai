package com.talentmatch.preferences;

import java.math.BigDecimal;
import java.util.Optional;

/** Salary amounts to a yearly figure (pure). No currency conversion. */
public final class SalaryNormalizer {

    private static final BigDecimal MONTHS_PER_YEAR = BigDecimal.valueOf(12);

    private SalaryNormalizer() {
    }

    /**
     * The yearly amount for YEAR (as is) and MONTH (× 12). DAY and HOUR give empty: working days
     * and hours vary too much to compare against a floor.
     */
    public static Optional<BigDecimal> annualize(BigDecimal amount, SalaryPeriod period) {
        if (amount == null || period == null) {
            return Optional.empty();
        }
        return switch (period) {
            case YEAR -> Optional.of(amount);
            case MONTH -> Optional.of(amount.multiply(MONTHS_PER_YEAR));
            case DAY, HOUR -> Optional.empty();
        };
    }
}
