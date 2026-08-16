package com.company.aidev.persistence.repository;

import com.company.aidev.persistence.entity.ReviewResultEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReviewResultRepository extends JpaRepository<ReviewResultEntity, UUID> {

    List<ReviewResultEntity> findByWorkflowIdOrderByCreatedAtAsc(UUID workflowId);
}
