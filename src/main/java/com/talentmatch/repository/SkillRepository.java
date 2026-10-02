package com.talentmatch.repository;

import com.talentmatch.domain.entity.Skill;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SkillRepository extends JpaRepository<Skill, UUID> {

    /** Case-insensitive lookup (same expression as uq_skill_name_lower). */
    @Query("select s from Skill s where lower(s.name) = lower(:name)")
    Optional<Skill> findByNameIgnoringCase(@Param("name") String name);

    /** Skills whose lower(name) is in the given (already lowercased) keys. */
    @Query("select s from Skill s where lower(s.name) in :keys")
    List<Skill> findAllByLowerNameIn(@Param("keys") Collection<String> keys);

    @Query(value = "select s from Skill s order by lower(s.name) asc, s.id asc",
            countQuery = "select count(s) from Skill s")
    Page<Skill> findListPage(Pageable pageable);

    /** {@code pattern} is a lowercase LIKE pattern escaped with '!'. */
    @Query(value = """
            select s from Skill s
            where lower(s.name) like :pattern escape '!'
            order by lower(s.name) asc, s.id asc""",
            countQuery = "select count(s) from Skill s where lower(s.name) like :pattern escape '!'")
    Page<Skill> searchListPage(@Param("pattern") String pattern, Pageable pageable);
}
