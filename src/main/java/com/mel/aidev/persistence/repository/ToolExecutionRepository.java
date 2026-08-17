package com.mel.aidev.persistence.repository;

import com.mel.aidev.persistence.entity.ToolExecutionEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ToolExecutionRepository extends JpaRepository<ToolExecutionEntity, UUID> {

    List<ToolExecutionEntity> findByWorkflowIdOrderByCreatedAtAsc(UUID workflowId);
}
