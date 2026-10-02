package com.talentmatch.repository;

import com.talentmatch.domain.entity.Candidate;
import com.talentmatch.repository.projection.CandidateListRow;
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

public interface CandidateRepository extends JpaRepository<Candidate, UUID> {

    /** Candidate with skills and skill rows in one query (detail view / update). */
    @EntityGraph(attributePaths = {"skills", "skills.skill"})
    Optional<Candidate> findWithSkillsById(UUID id);

    Optional<Candidate> findByEmail(String email);

    @Query(value = """
            select new com.talentmatch.repository.projection.CandidateListRow(c.id, c.fullName, c.email, c.summary)
            from Candidate c
            order by c.fullName asc, c.id asc""",
            countQuery = "select count(c) from Candidate c")
    Page<CandidateListRow> findListPage(Pageable pageable);

    @Query(value = """
            select new com.talentmatch.repository.projection.CandidateListRow(c.id, c.fullName, c.email, c.summary)
            from Candidate c
            where exists (select 1 from CandidateSkill cs join cs.skill s
                          where cs.candidate = c and lower(s.name) = lower(:skill))
            order by c.fullName asc, c.id asc""",
            countQuery = """
            select count(c) from Candidate c
            where exists (select 1 from CandidateSkill cs join cs.skill s
                          where cs.candidate = c and lower(s.name) = lower(:skill))""")
    Page<CandidateListRow> findListPageBySkill(@Param("skill") String skill, Pageable pageable);

    /** Re-reads updated_at from the database (it may have been bumped by the V2 link triggers). */
    @Query("select c.updatedAt from Candidate c where c.id = :id")
    Optional<Instant> findUpdatedAt(@Param("id") UUID id);

    /** Bulk delete; the database cascades candidate_skill and job_match rows. */
    @Modifying
    @Query("delete from Candidate c where c.id = :id")
    int deleteByIdReturningCount(@Param("id") UUID id);
}
