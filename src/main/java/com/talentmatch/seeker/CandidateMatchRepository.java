package com.talentmatch.seeker;

import com.talentmatch.domain.scoring.CandidateSkillFact;
import com.talentmatch.domain.scoring.JobRequirement;
import com.talentmatch.preferences.SalaryPeriod;
import com.talentmatch.preferences.Seniority;
import com.talentmatch.preferences.Workplace;
import com.talentmatch.repository.MatchJdbcRepository.StoredExplanation;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Candidate-side reads for {@code GET /api/candidates/{id}/matches} (§4.1–§4.3): the candidate, the
 * scope SQL shared by every query, the stale (or forced) scoring targets, job requirements for many
 * jobs, the candidate's skills, counts and the ranked page. Reads {@code feed_job}, never writes it;
 * score writes and locks are {@link com.talentmatch.repository.MatchJdbcRepository}'s. All methods
 * join the caller's transaction.
 */
@Repository
public class CandidateMatchRepository {

    /** {@code listedAt} (decision d): {@code j} = job, {@code f} = feed_job (LEFT JOIN). */
    static final String LISTED_AT = "CASE WHEN f.job_id IS NULL THEN j.created_at "
            + "WHEN f.posted_at IS NOT NULL THEN LEAST(f.posted_at, f.first_seen_at) "
            + "WHEN NOT f.baseline THEN f.first_seen_at "
            + "ELSE NULL END";

    /** Where {@link #LISTED_AT} comes from ({@link ListedAtSource} names). */
    static final String LISTED_AT_SOURCE = "CASE WHEN f.job_id IS NULL THEN 'CREATED' "
            + "WHEN f.posted_at IS NOT NULL THEN 'POSTED' "
            + "WHEN NOT f.baseline THEN 'FIRST_SEEN' "
            + "ELSE 'UNKNOWN' END";

    /** A job can be ranked only if it lists a skill (an old match row of a job that lost its skills never is). */
    static final String MATCHABLE = "EXISTS (SELECT 1 FROM job_skill js WHERE js.job_id = j.id)";

    private static final String SCOPE_FROM = " FROM job j LEFT JOIN feed_job f ON f.job_id = j.id";

    private static final String RANKED_FROM = " FROM job j"
            + " JOIN job_match m ON m.job_id = j.id AND m.candidate_id = :cid"
            + " LEFT JOIN feed_job f ON f.job_id = j.id";

    private static final String RANKED_COLUMNS = "j.id, j.title, j.company, j.description, j.origin, j.updated_at, "
            + "f.job_id AS feed_job_id, f.primary_url, f.first_seen_at, f.posted_at, f.closed_at, f.baseline, "
            + "f.workplace, f.location_text, array_to_string(f.country_codes, ',') AS country_codes, f.seniority, "
            + "f.salary_min, f.salary_max, f.salary_currency, f.salary_period, f.salary_estimated, "
            + "f.enrichment_status, f.preference_verdict, "
            + jsonNames("f.filter_reasons") + " AS filter_reasons, "
            + jsonNames("f.filter_flags") + " AS filter_flags, "
            + "m.score, m.computed_at, "
            + "m.seeker_explanation_payload ->> 'explanation' AS seeker_text, "
            + "CAST(m.seeker_explanation_payload AS text) AS seeker_payload, "
            + "m.seeker_explanation_input_hash, m.seeker_explanation_model, m.seeker_explanation_generated_at, "
            + LISTED_AT + " AS listed_at, " + LISTED_AT_SOURCE + " AS listed_at_source";

    private static final String ORDER_SCORE = " ORDER BY m.score DESC, listed_at DESC NULLS LAST, j.id ASC";
    private static final String ORDER_NEWEST = " ORDER BY listed_at DESC NULLS LAST, m.score DESC, j.id ASC";

    private final NamedParameterJdbcTemplate jdbc;

    public CandidateMatchRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ------------------------------------------------------------------ types

    /**
     * The candidate as the explanation layer needs it. No name or email: neither is ever sent to the
     * model for a seeker explanation (decision h).
     *
     * @param updatedAt candidate.updated_at (guards the explanation write)
     */
    public record CandidateHeader(UUID id, String summary, Instant updatedAt) {
    }

    /**
     * One ranked job for the candidate. Feed-only fields are null for MANUAL jobs ({@code countryCodes}
     * empty).
     *
     * @param jobUpdatedAt job.updated_at (guards the explanation write)
     * @param feed         true when the job has a feed_job row
     * @param preferenceVerdict the stored owner verdict (PASS, FILTERED or null); the service exposes it
     *                          to the owner only
     * @param filterReasons     the stored owner filter reasons (enum names, stored order); never null
     * @param filterFlags       the stored owner flags (enum names, stored order); never null
     * @param score        raw job_match.score (not rounded)
     * @param stored       the persisted seeker explanation (V6 columns), or null when there is none
     */
    public record RankedJobRow(UUID jobId, String title, String company, String description, String origin,
                               Instant jobUpdatedAt, boolean feed, String primaryUrl, Instant firstSeenAt,
                               Instant postedAt, Instant closedAt, Boolean baseline, Workplace workplace,
                               String locationText, List<String> countryCodes, Seniority seniority,
                               BigDecimal salaryMin, BigDecimal salaryMax, String salaryCurrency,
                               SalaryPeriod salaryPeriod, Boolean salaryEstimated, String enrichmentStatus,
                               String preferenceVerdict, List<String> filterReasons, List<String> filterFlags,
                               double score, Instant computedAt, Instant listedAt, ListedAtSource listedAtSource,
                               StoredExplanation stored) {
    }

    /**
     * The scope conditions of one request (§4.1), built once from the query and shared by the
     * target, count and page queries. Immutable; {@link #bind} adds its parameters to a query's source.
     */
    public static final class Scope {

        private final CandidateMatchQuery query;
        private final boolean hideFiltered;
        private final String where;

        private Scope(CandidateMatchQuery query, boolean hideFiltered) {
            this.query = query;
            this.hideFiltered = hideFiltered;
            this.where = buildWhere(query, hideFiltered);
        }

        /**
         * @param hideFiltered exclude jobs the owner's preferences filtered out
         *                     ({@code f.preference_verdict = 'FILTERED'})
         */
        public static Scope of(CandidateMatchQuery query, boolean hideFiltered) {
            return new Scope(Objects.requireNonNull(query, "query"), hideFiltered);
        }

        public CandidateMatchQuery query() {
            return query;
        }

        public boolean hideFiltered() {
            return hideFiltered;
        }

        /** The ANDed conditions over {@code j} (job) and {@code f} (feed_job, LEFT JOIN); never empty. */
        String where() {
            return where;
        }

        void bind(MapSqlParameterSource params) {
            if (query.postedSince() != null) {
                params.addValue("since", OffsetDateTime.ofInstant(query.postedSince(), ZoneOffset.UTC),
                        Types.TIMESTAMP_WITH_TIMEZONE);
            }
            if (!query.countries().isEmpty()) {
                params.addValue("countries", String.join(",", query.countries()));
            }
            if (query.location() != null) {
                params.addValue("loc", escapeLike(query.location()));
            }
        }

        private static String buildWhere(CandidateMatchQuery q, boolean hideFiltered) {
            List<String> c = new ArrayList<>();
            switch (q.origin()) {
                case FEED -> c.add("j.origin = 'FEED'");
                case MANUAL -> c.add("j.origin = 'MANUAL'");
                case ALL -> { }
            }
            if (!q.includeClosed()) {
                c.add("f.closed_at IS NULL");                        // true for MANUAL jobs (no feed row)
            }
            if (hideFiltered) {
                c.add("f.preference_verdict IS DISTINCT FROM 'FILTERED'");
            }
            if (q.postedSince() != null) {
                c.add("(" + LISTED_AT + ") >= :since");              // undated (NULL) jobs drop out
            }
            switch (q.remote()) {
                case ONLY -> c.add("f.workplace = 'REMOTE'");
                case EXCLUDE -> c.add("f.workplace IS DISTINCT FROM 'REMOTE'");
                case INCLUDE -> { }
            }
            if (!q.countries().isEmpty()) {
                // A MANUAL job or a feed job without country data passes; with remote=include (or only)
                // a REMOTE job passes whatever its countries.
                c.add("(f.job_id IS NULL OR cardinality(f.country_codes) = 0 "
                        + "OR f.country_codes && CAST(string_to_array(CAST(:countries AS text), ',') AS varchar(2)[])"
                        + (q.remote() == RemoteFilter.EXCLUDE ? "" : " OR f.workplace = 'REMOTE'") + ")");
            }
            if (q.location() != null) {
                c.add("f.location_text ILIKE '%' || :loc || '%' ESCAPE '\\'"); // MANUAL jobs drop out
            }
            return c.isEmpty() ? "TRUE" : String.join(" AND ", c);
        }
    }

    // ------------------------------------------------------------------ reads

    public Optional<CandidateHeader> findCandidate(UUID candidateId) {
        return jdbc.query("SELECT id, summary, updated_at FROM candidate WHERE id = :cid",
                new MapSqlParameterSource("cid", candidateId),
                (rs, i) -> new CandidateHeader(rs.getObject("id", UUID.class), rs.getString("summary"),
                        instant(rs, "updated_at"))).stream().findFirst();
    }

    /**
     * In-scope matchable jobs to score for the candidate, in PostgreSQL uuid order (§4.2): with
     * {@code forced} false only jobs without a match row or whose row is older than the candidate or the
     * job; with {@code forced} true every one. A deleted candidate yields none.
     *
     * @param restrictTo only these job ids (the re-check under the locks), or null for every job
     */
    public List<UUID> findTargetJobIds(UUID candidateId, Scope scope, boolean forced, Collection<UUID> restrictTo) {
        if (restrictTo != null && restrictTo.isEmpty()) {
            return List.of();
        }
        MapSqlParameterSource params = new MapSqlParameterSource("cid", candidateId);
        scope.bind(params);
        StringBuilder sql = new StringBuilder("SELECT j.id FROM job j JOIN candidate c ON c.id = :cid"
                + " LEFT JOIN feed_job f ON f.job_id = j.id"
                + " LEFT JOIN job_match m ON m.job_id = j.id AND m.candidate_id = c.id WHERE ")
                .append(scope.where()).append(" AND ").append(MATCHABLE);
        if (!forced) {
            sql.append(" AND (m.id IS NULL OR m.computed_at < GREATEST(c.updated_at, j.updated_at))");
        }
        if (restrictTo != null) {
            sql.append(" AND j.id IN (:ids)");
            params.addValue("ids", List.copyOf(restrictTo));
        }
        sql.append(" ORDER BY j.id");
        return jdbc.queryForList(sql.toString(), params, UUID.class);
    }

    /** The skills of these jobs (one query), by job; jobs without skills are absent from the map. */
    public Map<UUID, List<JobRequirement>> requirementsFor(Collection<UUID> jobIds) {
        Map<UUID, List<JobRequirement>> out = new LinkedHashMap<>();
        if (jobIds.isEmpty()) {
            return out;
        }
        jdbc.query("SELECT js.job_id, js.skill_id, s.name, js.required FROM job_skill js "
                        + "JOIN skill s ON s.id = js.skill_id WHERE js.job_id IN (:ids) ORDER BY js.job_id",
                new MapSqlParameterSource("ids", List.copyOf(jobIds)), rs -> {
                    out.computeIfAbsent(rs.getObject("job_id", UUID.class), k -> new ArrayList<>())
                            .add(new JobRequirement(rs.getObject("skill_id", UUID.class), rs.getString("name"),
                                    rs.getBoolean("required")));
                });
        return out;
    }

    /** All of the candidate's skills (empty for a deleted candidate). */
    public List<CandidateSkillFact> candidateFacts(UUID candidateId) {
        return jdbc.query("SELECT skill_id, years_experience FROM candidate_skill WHERE candidate_id = :cid",
                new MapSqlParameterSource("cid", candidateId), (rs, i) -> {
                    int years = rs.getInt("years_experience");
                    return new CandidateSkillFact(rs.getObject("skill_id", UUID.class), rs.wasNull() ? null : years);
                });
    }

    /** In-scope matchable jobs with a match row for the candidate scoring at least {@code minScore}. */
    public long countRanked(UUID candidateId, Scope scope, double minScore) {
        MapSqlParameterSource params = new MapSqlParameterSource("cid", candidateId).addValue("minScore", minScore);
        scope.bind(params);
        Long count = jdbc.queryForObject("SELECT count(*)" + RANKED_FROM + " WHERE " + scope.where()
                + " AND " + MATCHABLE + " AND m.score >= :minScore", params, Long.class);
        return count == null ? 0L : count;
    }

    /** One page of {@link #countRanked}'s jobs in the requested order (stable across pages and ties). */
    public List<RankedJobRow> findRankedPage(UUID candidateId, Scope scope, double minScore, Sort sort, int limit,
                                             long offset) {
        MapSqlParameterSource params = new MapSqlParameterSource("cid", candidateId)
                .addValue("minScore", minScore)
                .addValue("limit", limit)
                .addValue("offset", offset);
        scope.bind(params);
        String sql = "SELECT " + RANKED_COLUMNS + RANKED_FROM + " WHERE " + scope.where() + " AND " + MATCHABLE
                + " AND m.score >= :minScore" + (sort == Sort.NEWEST ? ORDER_NEWEST : ORDER_SCORE)
                + " LIMIT :limit OFFSET :offset";
        return jdbc.query(sql, params, (rs, i) -> rankedRow(rs));
    }

    /** In-scope jobs without skills (not ranked). */
    public long countUnmatchable(Scope scope) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        scope.bind(params);
        Long count = jdbc.queryForObject("SELECT count(*)" + SCOPE_FROM + " WHERE " + scope.where()
                + " AND NOT " + MATCHABLE, params, Long.class);
        return count == null ? 0L : count;
    }

    /** True if any in-scope job lists a skill (whatever the candidate's scores). */
    public boolean anyMatchable(Scope scope) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        scope.bind(params);
        Boolean exists = jdbc.queryForObject("SELECT EXISTS (SELECT 1" + SCOPE_FROM + " WHERE " + scope.where()
                + " AND " + MATCHABLE + ")", params, Boolean.class);
        return Boolean.TRUE.equals(exists);
    }

    // ------------------------------------------------------------------ helpers

    /**
     * A jsonb array of enum names ({@code feed_job.filter_reasons}/{@code filter_flags}) as one
     * comma-separated string in array order; NULL for an empty array or a missing feed row.
     */
    private static String jsonNames(String column) {
        return "(SELECT string_agg(e.v, ',' ORDER BY e.o) FROM jsonb_array_elements_text(" + column
                + ") WITH ORDINALITY AS e(v, o))";
    }

    /** Escapes LIKE wildcards ({@code \ % _}) for {@code ESCAPE '\'}. */
    static String escapeLike(String value) {
        StringBuilder sb = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (ch == '\\' || ch == '%' || ch == '_') {
                sb.append('\\');
            }
            sb.append(ch);
        }
        return sb.toString();
    }

    private static RankedJobRow rankedRow(ResultSet rs) throws SQLException {
        boolean feed = rs.getObject("feed_job_id") != null;
        String seekerText = rs.getString("seeker_text");
        StoredExplanation stored = seekerText == null ? null : new StoredExplanation(
                seekerText,
                rs.getString("seeker_payload"),
                rs.getString("seeker_explanation_input_hash"),
                rs.getString("seeker_explanation_model"),
                instant(rs, "seeker_explanation_generated_at"));
        String source = rs.getString("listed_at_source");
        return new RankedJobRow(
                rs.getObject("id", UUID.class),
                rs.getString("title"),
                rs.getString("company"),
                rs.getString("description"),
                rs.getString("origin"),
                instant(rs, "updated_at"),
                feed,
                rs.getString("primary_url"),
                instant(rs, "first_seen_at"),
                instant(rs, "posted_at"),
                instant(rs, "closed_at"),
                feed ? rs.getBoolean("baseline") : null,
                feed ? Workplace.valueOf(rs.getString("workplace")) : null,
                rs.getString("location_text"),
                codes(rs.getString("country_codes")),
                feed ? Seniority.valueOf(rs.getString("seniority")) : null,
                rs.getBigDecimal("salary_min"),
                rs.getBigDecimal("salary_max"),
                trimmed(rs.getString("salary_currency")),
                period(rs.getString("salary_period")),
                feed ? rs.getBoolean("salary_estimated") : null,
                rs.getString("enrichment_status"),
                trimmed(rs.getString("preference_verdict")),
                codes(rs.getString("filter_reasons")),
                codes(rs.getString("filter_flags")),
                rs.getDouble("score"),
                instant(rs, "computed_at"),
                instant(rs, "listed_at"),
                ListedAtSource.valueOf(source),
                stored);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static List<String> codes(String joined) {
        if (joined == null || joined.isBlank()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String c : joined.split(",")) {
            String s = c.strip();
            if (!s.isEmpty()) {
                out.add(s);
            }
        }
        return List.copyOf(out);
    }

    private static String trimmed(String value) {
        return value == null ? null : value.strip();
    }

    private static SalaryPeriod period(String value) {
        return value == null ? null : SalaryPeriod.valueOf(value.strip());
    }
}
