package com.mel.aidev.api.dto;

import com.mel.aidev.persistence.entity.WorkflowEntity;
import com.mel.aidev.domain.BuildProfile;
import com.mel.aidev.workflow.WorkflowStatus;
import java.time.Instant;
import java.util.UUID;

/** Summary view of a workflow. */
public record WorkflowResponse(
        UUID id,
        UUID projectId,
        String jiraTicket,
        String requestId,
        String sourceMessage,
        String humanClarification,
        String gitlabProject,
        String sandboxImage,
        BuildProfile buildProfile,
        WorkflowStatus status,
        String branch,
        String baseBranch,
        Long mergeRequestIid,
        String mergeRequestUrl,
        int developmentAttempts,
        int pipelineAttempts,
        int reviewAttempts,
        String failureReason,
        Instant createdAt,
        Instant updatedAt,
        Instant finishedAt,
        Instant archivedAt,
        Instant purgedAt) {

    public static WorkflowResponse from(WorkflowEntity entity) {
        return new WorkflowResponse(
                entity.getId(),
                entity.getProjectId(),
                entity.isJiraBacked() ? entity.getJiraTicket() : null,
                entity.isJiraBacked() ? null : entity.getJiraTicket(),
                entity.getSourceMessage(),
                entity.getHumanClarification(),
                entity.getGitlabProject(),
                entity.getSandboxImage(),
                entity.getBuildProfile(),
                entity.getStatus(),
                entity.getBranch(),
                entity.getBaseBranch(),
                entity.getMergeRequestIid(),
                entity.getMergeRequestUrl(),
                entity.getDevelopmentAttempts(),
                entity.getPipelineAttempts(),
                entity.getReviewAttempts(),
                entity.getFailureReason(),
                entity.getCreatedAt(),
                entity.getUpdatedAt(),
                entity.getFinishedAt(),
                entity.getArchivedAt(),
                entity.getPurgedAt());
    }
}
