package com.talentmatch.feed;

import static com.talentmatch.feed.JobPostingRepository.chunks;
import static com.talentmatch.feed.JobPostingRepository.instant;
import static com.talentmatch.feed.JobPostingRepository.ts;

import java.sql.Types;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * JDBC access to {@code feed_job} and its {@code job} row (origin FEED). Step 6 covers what the
 * poller writes: create / attach by dedup key, canonical fields, open / close, and
 * {@code process_after} (the hand-off to the step-7 processor).
 */
@Repository
public class FeedJobRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public FeedJobRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** A feed job's key and whether it is closed. */
    public record JobRef(UUID jobId, String dedupKey, Instant closedAt) {
    }

    /** The open feed job with this dedup key (the partial unique index allows at most one). */
    public Optional<UUID> findOpenByDedupKey(String dedupKey) {
        return jdbc.queryForList("SELECT job_id FROM feed_job WHERE dedup_key = :key AND closed_at IS NULL",
                new MapSqlParameterSource("key", dedupKey), UUID.class).stream().findFirst();
    }

    public Optional<JobRef> findRef(UUID jobId) {
        return jdbc.query("SELECT job_id, dedup_key, closed_at FROM feed_job WHERE job_id = :id",
                new MapSqlParameterSource("id", jobId),
                (rs, n) -> new JobRef(rs.getObject("job_id", UUID.class), rs.getString("dedup_key"),
                        instant(rs, "closed_at"))).stream().findFirst();
    }

    /**
     * Creates a FEED job and its feed_job row for a new posting. The canonical fields are filled by
     * {@link #applyCanonical} afterwards (same transaction).
     *
     * @return the new job id, or empty when another transaction created an open job with this key
     *         first (the partial unique index decided; this method then removes its own job row)
     */
    public Optional<UUID> create(NormalizedPosting p, String dedupKey, boolean baseline, Instant now) {
        UUID jobId = jdbc.queryForObject("INSERT INTO job (title, company, description, origin) "
                        + "VALUES (:title, :company, :description, 'FEED') RETURNING id",
                new MapSqlParameterSource("title", p.title()).addValue("company", p.company())
                        .addValue("description", p.description(), Types.VARCHAR), UUID.class);
        List<UUID> inserted = jdbc.queryForList("INSERT INTO feed_job (job_id, dedup_key, first_seen_at, posted_at, "
                        + "baseline, primary_url, process_after) VALUES (:id, :key, :now, :postedAt, :baseline, :url, :now) "
                        + "ON CONFLICT (dedup_key) WHERE closed_at IS NULL DO NOTHING RETURNING job_id",
                new MapSqlParameterSource("id", jobId)
                        .addValue("key", dedupKey)
                        .addValue("now", ts(now), Types.TIMESTAMP)
                        .addValue("postedAt", ts(p.postedAt()), Types.TIMESTAMP)
                        .addValue("baseline", baseline)
                        .addValue("url", p.url()), UUID.class);
        if (inserted.isEmpty()) {
            jdbc.update("DELETE FROM job WHERE id = :id AND origin = 'FEED'", new MapSqlParameterSource("id", jobId));
            return Optional.empty();
        }
        return Optional.of(jobId);
    }

    /** A posting that isn't baseline joined the job: the job is no longer baseline (§4.5). */
    public void clearBaseline(UUID jobId) {
        jdbc.update("UPDATE feed_job SET baseline = false WHERE job_id = :id AND baseline",
                new MapSqlParameterSource("id", jobId));
    }

    /**
     * Reopens a closed job, unless another open job holds its key (then the caller moves the posting
     * to that job).
     *
     * @return true when reopened
     */
    public boolean reopen(UUID jobId, Instant now) {
        return jdbc.update("UPDATE feed_job f SET closed_at = NULL, process_after = :now WHERE f.job_id = :id "
                        + "AND f.closed_at IS NOT NULL AND NOT EXISTS (SELECT 1 FROM feed_job o "
                        + "WHERE o.dedup_key = f.dedup_key AND o.closed_at IS NULL)",
                new MapSqlParameterSource("id", jobId).addValue("now", ts(now), Types.TIMESTAMP)) == 1;
    }

    /**
     * Closes the jobs among {@code jobIds} that have no open posting left.
     *
     * @return the jobs closed now
     */
    public Set<UUID> closeWithoutOpenPostings(Collection<UUID> jobIds, Instant now) {
        Set<UUID> closed = new LinkedHashSet<>();
        for (List<UUID> chunk : chunks(new LinkedHashSet<>(jobIds))) {
            closed.addAll(jdbc.queryForList("UPDATE feed_job f SET closed_at = :now WHERE f.job_id IN (:ids) "
                            + "AND f.closed_at IS NULL AND NOT EXISTS (SELECT 1 FROM job_posting p "
                            + "WHERE p.job_id = f.job_id AND p.closed_at IS NULL) RETURNING f.job_id",
                    new MapSqlParameterSource("ids", chunk).addValue("now", ts(now), Types.TIMESTAMP), UUID.class));
        }
        return closed;
    }

    /**
     * Writes the canonical fields (§4.4) and hands the job to the processor ({@code process_after}).
     * When the description changed ({@code description_hash} differs), AI enrichment starts over:
     * PENDING, attempts 0, no failure, no wait. The {@code job} row is only updated when its title,
     * company or description changed, so an unchanged job keeps its {@code updated_at} (cached
     * matches stay fresh).
     */
    public void applyCanonical(UUID jobId, CanonicalJob.Canonical c, Instant now) {
        jdbc.update("UPDATE job SET title = :title, company = :company, description = :description "
                        + "WHERE id = :id AND origin = 'FEED' AND (title IS DISTINCT FROM :title "
                        + "OR company IS DISTINCT FROM :company OR description IS DISTINCT FROM :description)",
                new MapSqlParameterSource("id", jobId).addValue("title", c.title()).addValue("company", c.company())
                        .addValue("description", c.description(), Types.VARCHAR));

        String changed = "description_hash IS DISTINCT FROM CAST(:hash AS char(64))";
        MapSqlParameterSource params = new MapSqlParameterSource("id", jobId)
                .addValue("hash", c.descriptionHash(), Types.VARCHAR)
                .addValue("postedAt", ts(c.postedAt()), Types.TIMESTAMP)
                .addValue("url", c.primaryUrl())
                .addValue("location", c.locationText(), Types.VARCHAR)
                .addValue("countries", String.join(",", c.countryCodes()))
                .addValue("workplace", c.workplace().name())
                .addValue("employmentType", c.employmentType(), Types.VARCHAR)
                .addValue("seniority", c.seniority().name())
                .addValue("salaryMin", c.salaryMin(), Types.NUMERIC)
                .addValue("salaryMax", c.salaryMax(), Types.NUMERIC)
                .addValue("currency", c.salaryCurrency(), Types.VARCHAR)
                .addValue("period", c.salaryPeriod() == null ? null : c.salaryPeriod().name(), Types.VARCHAR)
                .addValue("estimated", c.salaryEstimated())
                .addValue("now", ts(now), Types.TIMESTAMP);
        jdbc.update("UPDATE feed_job SET posted_at = :postedAt, primary_url = :url, location_text = :location, "
                + "country_codes = CAST(string_to_array(CAST(:countries AS text), ',') AS varchar(2)[]), "
                + "workplace = :workplace, employment_type = :employmentType, seniority = :seniority, "
                + "salary_min = :salaryMin, salary_max = :salaryMax, salary_currency = :currency, "
                + "salary_period = :period, salary_estimated = :estimated, "
                + "enrichment_status = CASE WHEN " + changed + " THEN 'PENDING' ELSE enrichment_status END, "
                + "enrichment_attempts = CASE WHEN " + changed + " THEN 0 ELSE enrichment_attempts END, "
                + "enrichment_failure = CASE WHEN " + changed + " THEN NULL ELSE enrichment_failure END, "
                + "enrichment_not_before = CASE WHEN " + changed + " THEN NULL ELSE enrichment_not_before END, "
                + "description_hash = CAST(:hash AS char(64)), process_after = :now "
                + "WHERE job_id = :id", params);
    }

    /** Feed jobs waiting for the processor (step 7). */
    public long countPendingProcessing() {
        Long count = jdbc.queryForObject("SELECT count(*) FROM feed_job WHERE process_after IS NOT NULL",
                new MapSqlParameterSource(), Long.class);
        return count == null ? 0 : count;
    }
}
