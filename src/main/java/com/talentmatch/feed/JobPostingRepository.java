package com.talentmatch.feed;

import com.talentmatch.feed.source.SourceKind;
import com.talentmatch.preferences.SalaryPeriod;
import com.talentmatch.preferences.Workplace;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * JDBC access to {@code job_posting} (V5; not mapped in JPA). Called by the poller inside its one
 * transaction per source. Statements over many external ids are chunked to keep the bind-parameter
 * count low.
 */
@Repository
public class JobPostingRepository {

    static final int CHUNK = 1000;

    private final NamedParameterJdbcTemplate jdbc;

    public JobPostingRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * What the poller needs to know about a stored posting.
     *
     * @param hasDescription  false while the description is pending (Greenhouse detail not fetched yet)
     * @param sourceUpdatedAt the provider's change marker at the last content write
     */
    public record StoredPosting(UUID id, String externalId, UUID jobId, String contentHash, Instant sourceUpdatedAt,
                                boolean hasDescription, Instant closedAt) {

        public boolean open() {
            return closedAt == null;
        }
    }

    /** Every posting of the source, open or closed. */
    public List<StoredPosting> findBySource(UUID sourceId) {
        return jdbc.query("SELECT id, external_id, job_id, content_hash, source_updated_at, "
                        + "(description IS NOT NULL) AS has_description, closed_at FROM job_posting WHERE source_id = :sid",
                new MapSqlParameterSource("sid", sourceId),
                (rs, n) -> new StoredPosting(rs.getObject("id", UUID.class), rs.getString("external_id"),
                        rs.getObject("job_id", UUID.class), rs.getString("content_hash"),
                        instant(rs, "source_updated_at"), rs.getBoolean("has_description"), instant(rs, "closed_at")));
    }

    /** Inserts a new posting attached to {@code jobId}. */
    public UUID insert(UUID sourceId, UUID jobId, NormalizedPosting p, boolean baseline, Instant now) {
        MapSqlParameterSource params = content(p)
                .addValue("sid", sourceId)
                .addValue("jobId", jobId)
                .addValue("externalId", p.externalId())
                .addValue("baseline", baseline)
                .addValue("now", ts(now), Types.TIMESTAMP);
        return jdbc.queryForObject("INSERT INTO job_posting (source_id, external_id, job_id, url, title, company, "
                + "description, location_text, country_code, workplace, employment_type, salary_min, salary_max, "
                + "salary_currency, salary_period, salary_estimated, posted_at, source_updated_at, first_seen_at, "
                + "last_seen_at, baseline, content_hash) VALUES (:sid, :externalId, :jobId, :url, :title, :company, "
                + ":description, :location, :country, :workplace, :employmentType, :salaryMin, :salaryMax, "
                + ":currency, :period, :estimated, :postedAt, :sourceUpdatedAt, :now, :now, :baseline, :hash) "
                + "RETURNING id", params, UUID.class);
    }

    /** Replaces the content of a posting (it stays attached to its job). */
    public void update(UUID postingId, NormalizedPosting p, Instant now) {
        jdbc.update("UPDATE job_posting SET url = :url, title = :title, company = :company, description = :description, "
                        + "location_text = :location, country_code = :country, workplace = :workplace, "
                        + "employment_type = :employmentType, salary_min = :salaryMin, salary_max = :salaryMax, "
                        + "salary_currency = :currency, salary_period = :period, salary_estimated = :estimated, "
                        + "posted_at = :postedAt, source_updated_at = :sourceUpdatedAt, content_hash = :hash, "
                        + "last_seen_at = :now WHERE id = :id",
                content(p).addValue("id", postingId).addValue("now", ts(now), Types.TIMESTAMP));
    }

    /** A closed posting seen again: open, attached to {@code jobId} (its old job or the open one with its key). */
    public void reopen(UUID postingId, UUID jobId, Instant now) {
        jdbc.update("UPDATE job_posting SET closed_at = NULL, job_id = :jobId, last_seen_at = :now WHERE id = :id",
                new MapSqlParameterSource("id", postingId).addValue("jobId", jobId)
                        .addValue("now", ts(now), Types.TIMESTAMP));
    }

    /** Marks postings as seen in this poll (no content change). */
    public void touch(Collection<UUID> postingIds, Instant now) {
        for (List<UUID> chunk : chunks(postingIds)) {
            jdbc.update("UPDATE job_posting SET last_seen_at = :now WHERE id IN (:ids)",
                    new MapSqlParameterSource("ids", chunk).addValue("now", ts(now), Types.TIMESTAMP));
        }
    }

    /**
     * Closes the source's open postings with these external ids.
     *
     * @return the job id of every posting closed (one entry per posting)
     */
    public List<UUID> close(UUID sourceId, Collection<String> externalIds, Instant now) {
        List<UUID> jobIds = new ArrayList<>();
        for (List<String> chunk : chunks(externalIds)) {
            jobIds.addAll(jdbc.queryForList("UPDATE job_posting SET closed_at = :now WHERE source_id = :sid "
                            + "AND closed_at IS NULL AND external_id IN (:ids) RETURNING job_id",
                    new MapSqlParameterSource("sid", sourceId).addValue("ids", chunk)
                            .addValue("now", ts(now), Types.TIMESTAMP), UUID.class));
        }
        return jobIds;
    }

    public int countOpen(UUID sourceId) {
        Integer count = jdbc.queryForObject("SELECT count(*)::int FROM job_posting WHERE source_id = :sid "
                + "AND closed_at IS NULL", new MapSqlParameterSource("sid", sourceId), Integer.class);
        return count == null ? 0 : count;
    }

    /** The open postings of a job with their source kind, for {@link CanonicalJob}. */
    public List<CanonicalJob.Candidate> findOpenCandidates(UUID jobId) {
        return jdbc.query("SELECT p.id, s.kind, p.title, p.company, p.description, p.url, p.posted_at, "
                        + "p.first_seen_at, p.location_text, p.country_code, p.workplace, p.employment_type, "
                        + "p.salary_min, p.salary_max, p.salary_currency, p.salary_period, p.salary_estimated "
                        + "FROM job_posting p JOIN feed_source s ON s.id = p.source_id "
                        + "WHERE p.job_id = :jobId AND p.closed_at IS NULL",
                new MapSqlParameterSource("jobId", jobId),
                (rs, n) -> new CanonicalJob.Candidate(
                        rs.getObject("id", UUID.class),
                        SourceKind.valueOf(rs.getString("kind")),
                        rs.getString("title"),
                        rs.getString("company"),
                        rs.getString("description"),
                        rs.getString("url"),
                        instant(rs, "posted_at"),
                        instant(rs, "first_seen_at"),
                        rs.getString("location_text"),
                        trimmed(rs.getString("country_code")),
                        Workplace.valueOf(rs.getString("workplace")),
                        rs.getString("employment_type"),
                        rs.getBigDecimal("salary_min"),
                        rs.getBigDecimal("salary_max"),
                        trimmed(rs.getString("salary_currency")),
                        period(rs.getString("salary_period")),
                        rs.getBoolean("salary_estimated")));
    }

    private static MapSqlParameterSource content(NormalizedPosting p) {
        return new MapSqlParameterSource()
                .addValue("url", p.url())
                .addValue("title", p.title())
                .addValue("company", p.company())
                .addValue("description", p.description(), Types.VARCHAR)
                .addValue("location", p.locationText(), Types.VARCHAR)
                .addValue("country", p.countryCode(), Types.VARCHAR)
                .addValue("workplace", p.workplace().name())
                .addValue("employmentType", p.employmentType(), Types.VARCHAR)
                .addValue("salaryMin", p.salaryMin(), Types.NUMERIC)
                .addValue("salaryMax", p.salaryMax(), Types.NUMERIC)
                .addValue("currency", p.salaryCurrency(), Types.VARCHAR)
                .addValue("period", p.salaryPeriod() == null ? null : p.salaryPeriod().name(), Types.VARCHAR)
                .addValue("estimated", p.salaryEstimated())
                .addValue("postedAt", ts(p.postedAt()), Types.TIMESTAMP)
                .addValue("sourceUpdatedAt", ts(p.sourceUpdatedAt()), Types.TIMESTAMP)
                .addValue("hash", p.contentHash());
    }

    static <T> List<List<T>> chunks(Collection<T> values) {
        List<List<T>> chunks = new ArrayList<>();
        if (values == null || values.isEmpty()) {
            return chunks;
        }
        List<T> all = new ArrayList<>(values);
        for (int i = 0; i < all.size(); i += CHUNK) {
            chunks.add(all.subList(i, Math.min(all.size(), i + CHUNK)));
        }
        return chunks;
    }

    static Timestamp ts(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp ts = rs.getTimestamp(column);
        return ts == null ? null : ts.toInstant();
    }

    /** char(n) columns come back blank-padded. */
    private static String trimmed(String value) {
        return value == null ? null : value.strip();
    }

    private static SalaryPeriod period(String value) {
        return value == null ? null : SalaryPeriod.valueOf(value);
    }
}
