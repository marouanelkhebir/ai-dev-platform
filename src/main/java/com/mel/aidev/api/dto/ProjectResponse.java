package com.mel.aidev.api.dto;

import com.mel.aidev.persistence.entity.ProjectEntity;
import com.mel.aidev.workflow.WorkflowStatus;
import java.time.Instant;
import java.util.UUID;

/** Summary view of a project, for the list screen. */
public record ProjectResponse(
        UUID id,
        String name,
        String description,
        String gitlabProject,
        String jiraProjectKey,
        String dockerImage,
        String defaultBranch,
        boolean active,
        Instant archivedAt,
        long workflowCount,
        Instant lastWorkflowAt,
        WorkflowStatus lastWorkflowStatus,
        Instant createdAt,
        Instant updatedAt) {

    public static ProjectResponse from(
            ProjectEntity entity, long workflowCount, Instant lastWorkflowAt, WorkflowStatus lastWorkflowStatus) {
        return new ProjectResponse(
                entity.getId(),
                entity.getName(),
                entity.getDescription(),
                entity.getGitlabProject(),
                entity.getJiraProjectKey(),
                entity.getDockerImage(),
                entity.getDefaultBranch(),
                entity.isStartable(),
                entity.getArchivedAt(),
                workflowCount,
                lastWorkflowAt,
                lastWorkflowStatus,
                entity.getCreatedAt(),
                entity.getUpdatedAt());
    }
}
