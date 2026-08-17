package com.mel.aidev.persistence.repository;

import com.mel.aidev.persistence.entity.WorkflowEntity;
import com.mel.aidev.workflow.WorkflowStatus;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WorkflowRepository
        extends JpaRepository<WorkflowEntity, UUID>, JpaSpecificationExecutor<WorkflowEntity> {

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

    /**
     * Runnable workflows nobody owns, oldest first.
     *
     * <p>Paged on purpose. After an outage every workflow in the table matches, and an unbounded
     * result would be loaded into memory and submitted in one burst only for the executor queue to
     * shed most of it. A bounded slice per tick drains the backlog at the rate the pool can absorb,
     * and {@code order by updatedAt asc} keeps it fair.
     */
    @Query(
            """
            select w.id from WorkflowEntity w
            where w.status in :statuses
              and (w.claimedAt is null or w.claimedAt < :staleBefore)
            order by w.updatedAt asc
            """)
    List<UUID> findRunnableIds(
            @Param("statuses") Collection<WorkflowStatus> statuses,
            @Param("staleBefore") Instant staleBefore,
            Pageable pageable);

    /**
     * Refreshes the claim of a workflow the engine is still working on.
     *
     * <p>A bulk update rather than a read-modify-write: it leaves {@code @Version} alone, so the
     * detached entity the engine is holding for the duration of the run stays valid and its next
     * {@code save} does not fail on an optimistic lock. It also leaves {@code updatedAt} alone, which
     * keeps the fairness ordering of {@link #findRunnableIds} meaningful.
     */
    @Modifying
    @Query("update WorkflowEntity w set w.claimedAt = :now where w.id = :id")
    int refreshClaim(@Param("id") UUID id, @Param("now") Instant now);

    @Query(
            """
            select w.id from WorkflowEntity w
            where w.status = :status and w.updatedAt < :before
            """)
    List<UUID> findIdsStuckInStatus(@Param("status") WorkflowStatus status, @Param("before") Instant before);

    // ------------------------------------------------------------------ projects

    long countByProjectId(UUID projectId);

    @Query(
            """
            select count(w) from WorkflowEntity w
            where w.projectId = :projectId and w.status not in :terminalStatuses
            """)
    long countActiveByProjectId(
            @Param("projectId") UUID projectId,
            @Param("terminalStatuses") Collection<WorkflowStatus> terminalStatuses);

    Optional<WorkflowEntity> findFirstByProjectIdOrderByCreatedAtDesc(UUID projectId);

    Optional<WorkflowEntity> findByIdAndProjectId(UUID id, UUID projectId);

    // The filtered listings of the console and the project screen are built with
    // WorkflowSpecifications rather than with a query carrying nullable parameters: an optional
    // parameter written as ":x is null or ..." reaches PostgreSQL untyped and the statement is
    // refused at execution time. A specification emits only the predicates actually asked for.

    // ----------------------------------------------------------------- dashboard

    @Query(
            """
            select w.status as status, count(w) as count
            from WorkflowEntity w
            where w.projectId = :projectId
              and w.createdAt >= :from and w.createdAt < :to
            group by w.status
            """)
    List<StatusCount> countByStatusForProject(
            @Param("projectId") UUID projectId, @Param("from") Instant from, @Param("to") Instant to);

    /**
     * Start and end of every terminated workflow of the period.
     *
     * <p>The percentiles are computed in Java rather than in SQL: {@code percentile_cont} is not
     * portable across the engines this project runs on, and the row count per project and per period
     * is small enough that reading the timestamps costs less than a dialect-specific query.
     */
    @Query(
            """
            select w.createdAt as startedAt, w.finishedAt as finishedAt
            from WorkflowEntity w
            where w.projectId = :projectId
              and w.finishedAt is not null
              and w.createdAt >= :from and w.createdAt < :to
            """)
    List<DurationRow> durationsForProject(
            @Param("projectId") UUID projectId, @Param("from") Instant from, @Param("to") Instant to);

    @Query(
            """
            select w from WorkflowEntity w
            where w.projectId = :projectId
              and w.status = com.mel.aidev.workflow.WorkflowStatus.FAILED
              and w.createdAt >= :from and w.createdAt < :to
            order by coalesce(w.finishedAt, w.updatedAt) desc
            """)
    List<WorkflowEntity> recentFailures(
            @Param("projectId") UUID projectId,
            @Param("from") Instant from,
            @Param("to") Instant to,
            Pageable pageable);

    // ----------------------------------------------------------------- retention

    /**
     * Terminated workflows of a project whose detailed payloads may be purged.
     *
     * <p>{@code purgedAt is null} is the eligibility condition, which is what makes the job
     * idempotent: an interrupted purge simply resumes on the next round.
     */
    @Query(
            """
            select w.id from WorkflowEntity w
            where w.projectId = :projectId
              and w.purgedAt is null
              and w.finishedAt is not null
              and w.finishedAt < :before
            order by w.finishedAt asc
            """)
    List<UUID> findPurgeCandidates(
            @Param("projectId") UUID projectId, @Param("before") Instant before, Pageable pageable);

    /** One status and the number of workflows in it. */
    interface StatusCount {
        WorkflowStatus getStatus();

        long getCount();
    }

    /** Start and end of one terminated workflow. */
    interface DurationRow {
        Instant getStartedAt();

        Instant getFinishedAt();
    }
}
