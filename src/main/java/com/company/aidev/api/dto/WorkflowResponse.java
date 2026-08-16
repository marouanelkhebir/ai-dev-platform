package com.company.aidev.api.dto;

import com.company.aidev.persistence.entity.WorkflowEntity;
import com.company.aidev.workflow.WorkflowStatus;
import java.time.Instant;
import java.util.UUID;

/** Summary view of a workflow. */
public record WorkflowResponse(
        UUID id,
        String jiraTicket,
        String gitlabProject,
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
                entity.getJiraTicket(),
                entity.getGitlabProject(),
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
