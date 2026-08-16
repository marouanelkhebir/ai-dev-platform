package com.company.aidev.persistence.repository;

import com.company.aidev.persistence.entity.LlmExecutionEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LlmExecutionRepository extends JpaRepository<LlmExecutionEntity, UUID> {

    List<LlmExecutionEntity> findByWorkflowIdOrderByCreatedAtAsc(UUID workflowId);
}
