package com.company.aidev.persistence.repository;

import com.company.aidev.persistence.entity.AgentExecutionEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AgentExecutionRepository extends JpaRepository<AgentExecutionEntity, UUID> {

    List<AgentExecutionEntity> findByWorkflowIdOrderByStartedAtAsc(UUID workflowId);
}
