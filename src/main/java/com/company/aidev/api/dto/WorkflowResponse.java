package com.company.aidev.api.dto;

import com.company.aidev.persistence.entity.WorkflowEntity;
import com.company.aidev.domain.BuildProfile;
import com.company.aidev.workflow.WorkflowStatus;
import java.time.Instant;
import java.util.UUID;

/** Summary view of a workflow. */
public record WorkflowResponse(
        UUID id,
        String jiraTicket,
        String requestId,
        String sourceMessage,
        String humanClarification,
        String gitlabProject,
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
        Instant updatedAt) {

    public static WorkflowResponse from(WorkflowEntity entity) {
        return new WorkflowResponse(
                entity.getId(),
                entity.isJiraBacked() ? entity.getJiraTicket() : null,
                entity.isJiraBacked() ? null : entity.getJiraTicket(),
                entity.getSourceMessage(),
                entity.getHumanClarification(),
                entity.getGitlabProject(),
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
                entity.getUpdatedAt());
    }
}
