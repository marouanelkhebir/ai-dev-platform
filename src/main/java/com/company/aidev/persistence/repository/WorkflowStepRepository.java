package com.company.aidev.persistence.repository;

import com.company.aidev.persistence.entity.WorkflowStepEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WorkflowStepRepository extends JpaRepository<WorkflowStepEntity, UUID> {

    List<WorkflowStepEntity> findByWorkflowIdOrderBySequenceNumberAsc(UUID workflowId);

    @Query("select coalesce(max(s.sequenceNumber), 0) from WorkflowStepEntity s where s.workflowId = :workflowId")
    int findMaxSequenceNumber(@Param("workflowId") UUID workflowId);
}
