package com.campusmatch.repo;

import com.campusmatch.model.Job;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.transaction.annotation.Transactional;

public interface JobRepository extends JpaRepository<Job, Long> {

    @Query("""
        select distinct j from Job j
        where j.active = true
          and (lower(j.title) like :q or lower(j.company) like :q or lower(coalesce(j.description, '')) like :q)
          and lower(coalesce(j.location, '')) like :loc
          and (:company = '' or lower(j.company) = :company)
          and (:skill = '' or j.id in (select jr.id from Job jr join jr.requiredSkills rs where lower(rs) like :skill) or j.id in (select jp.id from Job jp join jp.preferredSkills ps where lower(ps) like :skill))
          and (:mode = '' or j.workMode = :mode)
          and (:type = '' or j.jobType = :type)
          and (:postedAfter is null or j.postedAt >= :postedAfter)
        """)
    Page<Job> search(@Param("q") String q, @Param("loc") String loc, @Param("company") String company, @Param("skill") String skill,
                     @Param("mode") String mode, @Param("type") String type, @Param("postedAfter") java.time.Instant postedAfter, Pageable pageable);

    List<Job> findByActiveTrue(Pageable pageable);
    long countBySource(String source);

    @Query("select distinct j.company from Job j where j.active = true order by lower(j.company)")
    List<String> findActiveCompanies();

    java.util.Optional<Job> findBySourceAndExternalId(String source, String externalId);

    @Modifying
    @Transactional
    @Query("update Job j set j.active = false where j.source = :source and j.lastSeenAt < :cutoff")
    int deactivateNotSeenSince(@Param("source") String source, @Param("cutoff") java.time.Instant cutoff);
}
