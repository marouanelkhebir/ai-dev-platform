package com.company.aidev.persistence.repository;

import com.company.aidev.persistence.entity.MergeRequestEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MergeRequestRepository extends JpaRepository<MergeRequestEntity, UUID> {

    List<MergeRequestEntity> findByWorkflowIdOrderByCreatedAtAsc(UUID workflowId);
}
