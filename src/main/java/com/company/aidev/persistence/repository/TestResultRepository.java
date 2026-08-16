package com.company.aidev.persistence.repository;

import com.company.aidev.persistence.entity.TestResultEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TestResultRepository extends JpaRepository<TestResultEntity, UUID> {

    List<TestResultEntity> findByWorkflowIdOrderByAttemptAsc(UUID workflowId);
}
