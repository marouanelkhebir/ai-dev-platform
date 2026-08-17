package com.mel.aidev.persistence.repository;

import com.mel.aidev.persistence.entity.ProjectEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProjectRepository extends JpaRepository<ProjectEntity, UUID> {

    @Query("select p from ProjectEntity p where lower(p.name) = lower(:name)")
    Optional<ProjectEntity> findByNameIgnoreCase(@Param("name") String name);

    /**
     * Projects that reference a GitLab repository and can still start work.
     *
     * <p>Returns a list rather than an optional: the repository is not unique across projects, and
     * the legacy API needs to tell "none" from "several" to answer with the right message.
     */
    @Query(
            """
            select p from ProjectEntity p
            where p.gitlabProject = :gitlabProject
              and p.active = true
              and p.archivedAt is null
            order by p.createdAt asc
            """)
    List<ProjectEntity> findStartableByGitlabProject(@Param("gitlabProject") String gitlabProject);

    @Query(
            """
            select p from ProjectEntity p
            where upper(p.jiraProjectKey) = upper(:key)
              and p.active = true
              and p.archivedAt is null
            order by p.createdAt asc
            """)
    List<ProjectEntity> findStartableByJiraProjectKey(@Param("key") String key);

    /**
     * Free-text search over the name, the repository and the Jira key.
     *
     * <p>{@code pattern} is always a LIKE pattern, {@code %} when nothing is searched. Passing a
     * nullable parameter to {@code concat} would leave PostgreSQL without a type to infer and the
     * query would be planned as {@code bytea}; the caller builds the pattern instead.
     */
    @Query(
            """
            select p from ProjectEntity p
            where (:activeOnly = false or (p.active = true and p.archivedAt is null))
              and (lower(p.name) like :pattern
                   or lower(p.gitlabProject) like :pattern
                   or lower(coalesce(p.jiraProjectKey, '')) like :pattern)
            order by p.name asc
            """)
    Page<ProjectEntity> search(
            @Param("pattern") String pattern, @Param("activeOnly") boolean activeOnly, Pageable pageable);

    /** Projects with a retention policy of their own, for the purge job. */
    @Query("select p from ProjectEntity p where p.retentionDays is not null")
    List<ProjectEntity> findWithRetentionPolicy();
}
