package com.company.aidev.persistence.repository;

import com.company.aidev.persistence.entity.ToolExecutionEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ToolExecutionRepository extends JpaRepository<ToolExecutionEntity, UUID> {

    List<ToolExecutionEntity> findByWorkflowIdOrderByCreatedAtAsc(UUID workflowId);
}
