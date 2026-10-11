package com.talentmatch.repository;

import com.talentmatch.domain.entity.Job;
import com.talentmatch.repository.projection.JobListRow;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface JobRepository extends JpaRepository<Job, UUID> {

    /** Job with skills and skill rows in one query (detail view / update). */
    @EntityGraph(attributePaths = {"skills", "skills.skill"})
    Optional<Job> findWithSkillsById(UUID id);

    /**
     * The MANUAL job with this natural key (V5: unique among MANUAL jobs only, partial index
     * uq_job_title_company_manual). FEED jobs may share a title and company and are ignored.
     */
    @Query("select j from Job j where j.title = :title and j.company = :company and j.origin = 'MANUAL'")
    Optional<Job> findManualByTitleAndCompany(@Param("title") String title, @Param("company") String company);

    @Query(value = """
            select new com.talentmatch.repository.projection.JobListRow(
                j.id, j.title, j.company, j.description, j.origin, count(js.id.skillId))
            from Job j left join j.skills js
            group by j.id, j.title, j.company, j.description, j.origin
            order by j.title asc, j.company asc, j.id asc""",
            countQuery = "select count(j) from Job j")
    Page<JobListRow> findListPage(Pageable pageable);

    /** Jobs listing the skill as required or nice-to-have; skillCount still counts all skills. */
    @Query(value = """
            select new com.talentmatch.repository.projection.JobListRow(
                j.id, j.title, j.company, j.description, j.origin, count(js.id.skillId))
            from Job j left join j.skills js
            where exists (select 1 from JobSkill f join f.skill fs
                          where f.job = j and lower(fs.name) = lower(:skill))
            group by j.id, j.title, j.company, j.description, j.origin
            order by j.title asc, j.company asc, j.id asc""",
            countQuery = """
            select count(j) from Job j
            where exists (select 1 from JobSkill f join f.skill fs
                          where f.job = j and lower(fs.name) = lower(:skill))""")
    Page<JobListRow> findListPageBySkill(@Param("skill") String skill, Pageable pageable);

    /** Re-reads updated_at from the database (it may have been bumped by the V2 link triggers). */
    @Query("select j.updatedAt from Job j where j.id = :id")
    Optional<Instant> findUpdatedAt(@Param("id") UUID id);

    /** The job's origin (MANUAL or FEED), if it exists. */
    @Query("select j.origin from Job j where j.id = :id")
    Optional<String> findOriginById(@Param("id") UUID id);

    /**
     * The source key of the job's earliest-seen posting (e.g. "greenhouse:acme"), for FEED jobs.
     * Source keys hold no secrets (Adzuna queries are hashed).
     */
    @Query(value = """
            SELECT s.source_key FROM job_posting p JOIN feed_source s ON s.id = p.source_id
            WHERE p.job_id = :id
            ORDER BY p.first_seen_at, p.id
            LIMIT 1""", nativeQuery = true)
    Optional<String> findFeedSourceKey(@Param("id") UUID id);

    /**
     * Bulk delete of a MANUAL job; the database cascades job_skill and job_match rows.
     * FEED jobs are never deleted here (0 rows): the job feed owns them.
     */
    @Modifying
    @Query("delete from Job j where j.id = :id and j.origin = 'MANUAL'")
    int deleteManualByIdReturningCount(@Param("id") UUID id);
}
