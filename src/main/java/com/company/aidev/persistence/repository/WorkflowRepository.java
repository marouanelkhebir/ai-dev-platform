package com.company.aidev.persistence.repository;

import com.company.aidev.persistence.entity.WorkflowEntity;
import com.company.aidev.workflow.WorkflowStatus;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WorkflowRepository extends JpaRepository<WorkflowEntity, UUID> {

    /**
     * Locks the workflow row for the duration of the transaction. Two engine threads (or two pods)
     * can therefore never advance the same workflow concurrently.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select w from WorkflowEntity w where w.id = :id")
    Optional<WorkflowEntity> findByIdForUpdate(@Param("id") UUID id);

    @Query(
            """
            select w from WorkflowEntity w
            where w.jiraTicket = :ticket
              and w.status not in :terminalStatuses
            """)
    List<WorkflowEntity> findActiveByJiraTicket(
            @Param("ticket") String ticket, @Param("terminalStatuses") Collection<WorkflowStatus> terminalStatuses);

    Optional<WorkflowEntity> findFirstByMergeRequestIidAndGitlabProjectOrderByCreatedAtDesc(
            Long mergeRequestIid, String gitlabProject);

    Optional<WorkflowEntity> findFirstByGitlabProjectAndBranchOrderByCreatedAtDesc(String gitlabProject, String branch);

    Page<WorkflowEntity> findAllByOrderByCreatedAtDesc(Pageable pageable);

    Page<WorkflowEntity> findAllByStatusOrderByCreatedAtDesc(WorkflowStatus status, Pageable pageable);

    @Query(
            """
            select w.id from WorkflowEntity w
            where w.status in :statuses
              and (w.claimedAt is null or w.claimedAt < :staleBefore)
            order by w.updatedAt asc
            """)
    List<UUID> findRunnableIds(
            @Param("statuses") Collection<WorkflowStatus> statuses, @Param("staleBefore") Instant staleBefore);

    @Query(
            """
            select w.id from WorkflowEntity w
            where w.status = :status and w.updatedAt < :before
            """)
    List<UUID> findIdsStuckInStatus(@Param("status") WorkflowStatus status, @Param("before") Instant before);
}
