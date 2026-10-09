package com.talentmatch.feed;

import com.talentmatch.feed.source.SourceKind;
import com.talentmatch.preferences.SalaryPeriod;
import com.talentmatch.preferences.Seniority;
import com.talentmatch.preferences.Workplace;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeSet;
import java.util.UUID;

/**
 * The canonical fields of a deduplicated feed job, chosen from its open postings (§4.4, pure).
 * <ul>
 *   <li>The canonical posting: ATS before aggregator, then a complete description, then the longest
 *       description, then the earliest first seen (ties by posting id, so the choice is stable).</li>
 *   <li>{@code job.title}, {@code job.company}, {@code job.description} and {@code primary_url} come
 *       from it.</li>
 *   <li>{@code posted_at}: the earliest known publish time of any posting.</li>
 *   <li>{@code country_codes}: the union; {@code workplace}: REMOTE if any posting is remote, else
 *       HYBRID, else ONSITE, else UNKNOWN.</li>
 *   <li>Salary: the canonical posting's, or else the first posting with a salary that isn't
 *       estimated.</li>
 * </ul>
 */
public final class CanonicalJob {

    private CanonicalJob() {
    }

    /** One open posting of the job, as stored (plus the kind of its source). */
    public record Candidate(UUID postingId, SourceKind kind, String title, String company, String description,
                            String url, Instant postedAt, Instant firstSeenAt, String locationText,
                            String countryCode, Workplace workplace, String employmentType, BigDecimal salaryMin,
                            BigDecimal salaryMax, String salaryCurrency, SalaryPeriod salaryPeriod,
                            boolean salaryEstimated) {

        public Candidate {
            Objects.requireNonNull(kind, "kind");
            workplace = workplace == null ? Workplace.UNKNOWN : workplace;
        }

        /** An ATS posting with its full description (an aggregator's text is only a snippet). */
        public boolean descriptionComplete() {
            return kind.ats() && description != null && !description.isBlank();
        }

        boolean hasSalary() {
            return salaryMin != null || salaryMax != null;
        }

        int descriptionLength() {
            return description == null ? 0 : description.length();
        }
    }

    /**
     * The values written to {@code job} and {@code feed_job}.
     *
     * @param descriptionHash sha256 of the description; null when there is none (pending)
     */
    public record Canonical(String title, String company, String description, String descriptionHash,
                            Instant postedAt, String primaryUrl, String locationText, List<String> countryCodes,
                            Workplace workplace, String employmentType, Seniority seniority, BigDecimal salaryMin,
                            BigDecimal salaryMax, String salaryCurrency, SalaryPeriod salaryPeriod,
                            boolean salaryEstimated) {

        public Canonical {
            countryCodes = countryCodes == null ? List.of() : List.copyOf(countryCodes);
        }
    }

    static final Comparator<Candidate> PREFERENCE = Comparator
            .comparing((Candidate c) -> !c.kind().ats())
            .thenComparing(c -> !c.descriptionComplete())
            .thenComparing(Candidate::descriptionLength, Comparator.reverseOrder())
            .thenComparing(Candidate::firstSeenAt, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(Candidate::postingId, Comparator.nullsLast(Comparator.naturalOrder()));

    /** Empty when the job has no open posting (it is closed). */
    public static Optional<Canonical> of(Collection<Candidate> openPostings) {
        if (openPostings == null || openPostings.isEmpty()) {
            return Optional.empty();
        }
        List<Candidate> ranked = new ArrayList<>(openPostings);
        ranked.removeIf(Objects::isNull);
        if (ranked.isEmpty()) {
            return Optional.empty();
        }
        ranked.sort(PREFERENCE);
        Candidate best = ranked.get(0);

        Instant postedAt = null;
        TreeSet<String> countries = new TreeSet<>();
        Workplace workplace = Workplace.UNKNOWN;
        for (Candidate c : ranked) {
            if (c.postedAt() != null && (postedAt == null || c.postedAt().isBefore(postedAt))) {
                postedAt = c.postedAt();
            }
            if (c.countryCode() != null) {
                countries.add(c.countryCode());
            }
            workplace = union(workplace, c.workplace());
        }

        Candidate salary = best.hasSalary() ? best
                : ranked.stream().filter(c -> c.hasSalary() && !c.salaryEstimated()).findFirst().orElse(null);
        String location = best.locationText() != null ? best.locationText()
                : ranked.stream().map(Candidate::locationText).filter(Objects::nonNull).findFirst().orElse(null);
        String employmentType = best.employmentType() != null ? best.employmentType()
                : ranked.stream().map(Candidate::employmentType).filter(Objects::nonNull).findFirst().orElse(null);

        String title = cut(best.title(), PostingNormalizer.MAX_TITLE);
        String description = best.description() == null || best.description().isBlank() ? null
                : PostingNormalizer.cutAtWord(best.description(), PostingNormalizer.MAX_DESCRIPTION);
        return Optional.of(new Canonical(
                title,
                cut(best.company(), PostingNormalizer.MAX_COMPANY),
                description,
                description == null ? null : PostingNormalizer.sha256Hex(description),
                postedAt,
                best.url(),
                location,
                List.copyOf(countries),
                workplace,
                employmentType,
                Seniority.fromTitle(title),
                salary == null ? null : salary.salaryMin(),
                salary == null ? null : salary.salaryMax(),
                salary == null ? null : salary.salaryCurrency(),
                salary == null ? null : salary.salaryPeriod(),
                salary != null && salary.salaryEstimated()));
    }

    /** REMOTE > HYBRID > ONSITE > UNKNOWN. */
    static Workplace union(Workplace a, Workplace b) {
        return rank(a) >= rank(b) ? a : b;
    }

    private static int rank(Workplace w) {
        if (w == null) {
            return 0;
        }
        return switch (w) {
            case REMOTE -> 3;
            case HYBRID -> 2;
            case ONSITE -> 1;
            case UNKNOWN -> 0;
        };
    }

    private static String cut(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max).strip();
    }
}
