package com.mel.aidev.persistence.repository;

import com.mel.aidev.persistence.entity.WorkflowStepLogEntity;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WorkflowStepLogRepository extends JpaRepository<WorkflowStepLogEntity, UUID> {

    List<WorkflowStepLogEntity> findByWorkflowIdOrderBySequenceNumberAsc(UUID workflowId);

    Optional<WorkflowStepLogEntity> findByWorkflowIdAndSequenceNumber(UUID workflowId, int sequenceNumber);

    /** Ids only, so a full download can inflate one step at a time instead of all of them at once. */
    @Query("select l.id from WorkflowStepLogEntity l where l.workflowId = :workflowId order by l.sequenceNumber asc")
    List<UUID> findIdsByWorkflowIdOrderBySequenceNumber(@Param("workflowId") UUID workflowId);

    /**
     * Deletes the logs of workflows that ended before a date, in bulk.
     *
     * <p>Bulk on purpose: these rows are large and there is nothing to summarise before dropping
     * them, unlike the audit payloads the retention job rewrites row by row.
     */
    @Modifying
    @Query(
            """
            delete from WorkflowStepLogEntity l
            where l.workflowId in (
                select w.id from WorkflowEntity w
                where w.finishedAt is not null and w.finishedAt < :before
            )
            """)
    int deleteByWorkflowFinishedBefore(@Param("before") Instant before);
}
