package com.talentmatch.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.talentmatch.support.AbstractApiIT;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * §8.2 V2 triggers: link INSERT/UPDATE/DELETE bump the parent's updated_at (each JdbcTemplate
 * statement is its own transaction, so now() advances); ETL-style no-op reruns change nothing,
 * so the next GET recomputes 0. SQL copied from scripts/etl/talentmatch_etl/loader.py.
 */
class StalenessTriggerIT extends AbstractApiIT {

    // loader.py SQL_UPSERT_CANDIDATE / SQL_UPSERT_JOB / SQL_UPSERT_CANDIDATE_SKILL / SQL_UPSERT_JOB_SKILL /
    // SQL_PRUNE_* (psycopg %s placeholders replaced by JDBC ?)
    private static final String UPSERT_CANDIDATE = """
            INSERT INTO candidate (full_name, email, summary)
            SELECT * FROM unnest(?::text[], ?::text[], ?::text[])
            ON CONFLICT (email) DO UPDATE
                SET full_name = EXCLUDED.full_name, summary = EXCLUDED.summary
                WHERE (candidate.full_name, candidate.summary)
                      IS DISTINCT FROM (EXCLUDED.full_name, EXCLUDED.summary)
            RETURNING (xmax = 0) AS inserted""";
    private static final String UPSERT_JOB = """
            INSERT INTO job (title, company, description)
            SELECT * FROM unnest(?::text[], ?::text[], ?::text[])
            ON CONFLICT (title, company) DO UPDATE
                SET description = EXCLUDED.description
                WHERE job.description IS DISTINCT FROM EXCLUDED.description
            RETURNING (xmax = 0) AS inserted""";
    private static final String UPSERT_CANDIDATE_SKILL = """
            INSERT INTO candidate_skill (candidate_id, skill_id, years_experience)
            SELECT * FROM unnest(?::uuid[], ?::uuid[], ?::int[])
            ON CONFLICT (candidate_id, skill_id) DO UPDATE
                SET years_experience = EXCLUDED.years_experience
                WHERE candidate_skill.years_experience IS DISTINCT FROM EXCLUDED.years_experience
            RETURNING (xmax = 0) AS inserted""";
    private static final String UPSERT_JOB_SKILL = """
            INSERT INTO job_skill (job_id, skill_id, required)
            SELECT * FROM unnest(?::uuid[], ?::uuid[], ?::boolean[])
            ON CONFLICT (job_id, skill_id) DO UPDATE
                SET required = EXCLUDED.required
                WHERE job_skill.required IS DISTINCT FROM EXCLUDED.required
            RETURNING (xmax = 0) AS inserted""";
    private static final String PRUNE_CANDIDATE_SKILL = """
            DELETE FROM candidate_skill cs
            WHERE cs.candidate_id = ANY(?::uuid[])
              AND NOT EXISTS (
                  SELECT 1 FROM unnest(?::uuid[], ?::uuid[]) AS k(c, s)
                  WHERE k.c = cs.candidate_id AND k.s = cs.skill_id
              )""";
    private static final String PRUNE_JOB_SKILL = """
            DELETE FROM job_skill js
            WHERE js.job_id = ANY(?::uuid[])
              AND NOT EXISTS (
                  SELECT 1 FROM unnest(?::uuid[], ?::uuid[]) AS k(j, s)
                  WHERE k.j = js.job_id AND k.s = js.skill_id
              )""";

    UUID java;
    UUID sql;
    UUID docker;

    @BeforeEach
    void skills() {
        java = skill("Java");
        sql = skill("SQL");
        docker = skill("Docker");
    }

    private static String arr(Object... values) {
        StringBuilder sb = new StringBuilder("{");
        for (int i = 0; i < values.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(values[i] == null ? "NULL" : "\"" + values[i] + "\"");
        }
        return sb.append('}').toString();
    }

    @Test
    void candidateLinkInsertUpdateDeleteBumpParent() {
        UUID c = candidate("Ada Lovelace", "ada@example.com");
        Instant t0 = updatedAt("candidate", c);

        jdbc.update("INSERT INTO candidate_skill (candidate_id, skill_id, years_experience) VALUES (?, ?, 3)", c, java);
        Instant t1 = updatedAt("candidate", c);
        assertThat(t1).isAfter(t0);

        jdbc.update("UPDATE candidate_skill SET years_experience = 4 WHERE candidate_id = ? AND skill_id = ?", c, java);
        Instant t2 = updatedAt("candidate", c);
        assertThat(t2).isAfter(t1);

        jdbc.update("DELETE FROM candidate_skill WHERE candidate_id = ? AND skill_id = ?", c, java);
        Instant t3 = updatedAt("candidate", c);
        assertThat(t3).isAfter(t2);

        // A statement touching zero link rows bumps nothing.
        jdbc.update("DELETE FROM candidate_skill WHERE candidate_id = ?", c);
        assertThat(updatedAt("candidate", c)).isEqualTo(t3);
    }

    @Test
    void jobLinkInsertUpdateDeleteBumpParent() {
        UUID j = jobWithoutSkills("Backend Engineer", "Acme");
        Instant t0 = updatedAt("job", j);

        jdbc.update("INSERT INTO job_skill (job_id, skill_id, required) VALUES (?, ?, true)", j, java);
        Instant t1 = updatedAt("job", j);
        assertThat(t1).isAfter(t0);

        jdbc.update("UPDATE job_skill SET required = false WHERE job_id = ? AND skill_id = ?", j, java);
        Instant t2 = updatedAt("job", j);
        assertThat(t2).isAfter(t1);

        jdbc.update("DELETE FROM job_skill WHERE job_id = ?", j);
        assertThat(updatedAt("job", j)).isAfter(t2);
    }

    @Test
    void skillDeleteCascadesAndMakesMatchesStale() {
        UUID c = candidate("Ada Lovelace", "ada@example.com", "Java", "SQL");
        UUID j = job("Backend Engineer", "Acme", "Java", "~SQL");
        assertThat(matches(j).get("recomputedCandidates").asInt()).isEqualTo(1);
        Instant jobTs = updatedAt("job", j);
        Instant candTs = updatedAt("candidate", c);

        jdbc.update("DELETE FROM skill WHERE id = ?", sql);
        assertThat(updatedAt("job", j)).isAfter(jobTs);
        assertThat(updatedAt("candidate", c)).isAfter(candTs);
        var page = matches(j);
        assertThat(page.get("recomputedCandidates").asInt()).isEqualTo(1);
        assertThat(page.at("/matches/0/score").asDouble()).isEqualTo(1.0);
    }

    @Test
    void etlStyleNoOpRerunLeavesUpdatedAtAndMatchesFresh() {
        UUID c1 = candidate("Ada Lovelace", "ada@example.com", "Java:5", "SQL");
        UUID c2 = candidate("Grace Hopper", "grace@example.com", "Docker:2");
        UUID j = job("Backend Engineer", "Acme", "Java", "~Docker");
        jdbc.update("UPDATE job SET description = 'Build APIs' WHERE id = ?", j);

        assertThat(matches(j).get("recomputedCandidates").asInt()).isEqualTo(2);
        assertThat(matches(j).get("recomputedCandidates").asInt()).isZero();

        Instant c1Ts = updatedAt("candidate", c1);
        Instant c2Ts = updatedAt("candidate", c2);
        Instant jTs = updatedAt("job", j);

        // Rerun the loader's statements with identical values.
        int candRows = jdbc.query(UPSERT_CANDIDATE, rs -> {
            int n = 0;
            while (rs.next()) {
                n++;
            }
            return n;
        }, arr("Ada Lovelace", "Grace Hopper"), arr("ada@example.com", "grace@example.com"), arr(null, null));
        assertThat(candRows).isZero();
        int jobRows = jdbc.query(UPSERT_JOB, rs -> rs.next() ? 1 : 0,
                arr("Backend Engineer"), arr("Acme"), arr("Build APIs"));
        assertThat(jobRows).isZero();
        int csRows = jdbc.query(UPSERT_CANDIDATE_SKILL, rs -> rs.next() ? 1 : 0,
                arr(c1, c1, c2), arr(java, sql, docker), arr(5, null, 2));
        assertThat(csRows).isZero();
        int jsRows = jdbc.query(UPSERT_JOB_SKILL, rs -> rs.next() ? 1 : 0,
                arr(j, j), arr(java, docker), arr(true, false));
        assertThat(jsRows).isZero();
        assertThat(jdbc.update(PRUNE_CANDIDATE_SKILL, arr(c1, c2), arr(c1, c1, c2), arr(java, sql, docker))).isZero();
        assertThat(jdbc.update(PRUNE_JOB_SKILL, arr(j), arr(j, j), arr(java, docker))).isZero();

        assertThat(updatedAt("candidate", c1)).isEqualTo(c1Ts);
        assertThat(updatedAt("candidate", c2)).isEqualTo(c2Ts);
        assertThat(updatedAt("job", j)).isEqualTo(jTs);
        assertThat(matches(j).get("recomputedCandidates").asInt()).isZero();

        // A real ETL change (years for one link) makes exactly that candidate stale.
        int changed = jdbc.query(UPSERT_CANDIDATE_SKILL, rs -> rs.next() ? 1 : 0,
                arr(c1), arr(java), arr(6));
        assertThat(changed).isEqualTo(1);
        assertThat(updatedAt("candidate", c1)).isAfter(c1Ts);
        assertThat(matches(j).get("recomputedCandidates").asInt()).isEqualTo(1);

        // An ETL prune of one job link makes every candidate stale for that job.
        assertThat(jdbc.update(PRUNE_JOB_SKILL, arr(j), arr(j), arr(docker))).isEqualTo(1); // drops Java
        var page = matches(j);
        assertThat(page.get("recomputedCandidates").asInt()).isEqualTo(2);
        assertThat(candidateNames(page)).containsExactly("Grace Hopper", "Ada Lovelace");
    }
}
