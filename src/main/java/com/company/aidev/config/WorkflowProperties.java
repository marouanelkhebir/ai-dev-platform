package com.company.aidev.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Guard rails of the orchestration engine.
 *
 * <p>Every automatic loop is bounded: an agent that cannot converge must hand the ticket back to a
 * human instead of burning GPU time.
 */
@ConfigurationProperties(prefix = "workflow")
public record WorkflowProperties(
        Integer maxDevelopmentAttempts,
        Integer maxPipelineAttempts,
        Integer maxReviewAttempts,
        Duration stepTimeout,
        Duration humanApprovalTimeout,
        Duration staleWorkflowTimeout,
        Integer executorPoolSize,
        Boolean autoStartFromJiraWebhook,
        Boolean allowAutoMerge) {

    public WorkflowProperties {
        maxDevelopmentAttempts = maxDevelopmentAttempts == null ? 3 : maxDevelopmentAttempts;
        maxPipelineAttempts = maxPipelineAttempts == null ? 3 : maxPipelineAttempts;
        maxReviewAttempts = maxReviewAttempts == null ? 3 : maxReviewAttempts;
        stepTimeout = stepTimeout == null ? Duration.ofMinutes(45) : stepTimeout;
        humanApprovalTimeout = humanApprovalTimeout == null ? Duration.ofDays(7) : humanApprovalTimeout;
        staleWorkflowTimeout = staleWorkflowTimeout == null ? Duration.ofMinutes(90) : staleWorkflowTimeout;
        executorPoolSize = executorPoolSize == null ? 4 : executorPoolSize;
        autoStartFromJiraWebhook = autoStartFromJiraWebhook == null || autoStartFromJiraWebhook;
        // Automatic merge is deliberately disabled in v1 and the engine refuses to enable it.
        allowAutoMerge = allowAutoMerge != null && allowAutoMerge;
    }
}
