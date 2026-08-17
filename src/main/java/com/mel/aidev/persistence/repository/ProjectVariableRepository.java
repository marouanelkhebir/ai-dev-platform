package com.mel.aidev.persistence.repository;

import com.mel.aidev.persistence.entity.ProjectVariableEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProjectVariableRepository extends JpaRepository<ProjectVariableEntity, UUID> {

    List<ProjectVariableEntity> findByProjectIdOrderByNameAsc(UUID projectId);

    Optional<ProjectVariableEntity> findByProjectIdAndName(UUID projectId, String name);

    void deleteByProjectIdAndName(UUID projectId, String name);
}
