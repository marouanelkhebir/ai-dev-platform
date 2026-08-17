package com.mel.aidev.persistence.repository;

import com.mel.aidev.persistence.entity.ProjectModelEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProjectModelRepository extends JpaRepository<ProjectModelEntity, ProjectModelEntity.Key> {

    @Query("select m from ProjectModelEntity m where m.key.projectId = :projectId")
    List<ProjectModelEntity> findByProjectId(@Param("projectId") UUID projectId);

    @org.springframework.data.jpa.repository.Modifying
    @Query("delete from ProjectModelEntity m where m.key.projectId = :projectId")
    void deleteByProjectId(@Param("projectId") UUID projectId);
}
