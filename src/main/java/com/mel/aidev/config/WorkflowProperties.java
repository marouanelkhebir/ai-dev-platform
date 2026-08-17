package com.mel.aidev.config;

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
        Boolean allowAutoMerge,
        Retention retention,
        Logs logs) {

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
        retention = retention == null ? new Retention(null, null, null) : retention;
        logs = logs == null ? new Logs(null, null, null) : logs;
    }

    /**
     * Default retention of the detailed audit payloads.
     *
     * <p>Prompts, raw model answers and tool results hold source code of the repository. They are
     * the bulk of the volume and the most sensitive part of the trail, so their lifetime is bounded
     * by default; a project can override it, including with 0 to keep them forever.
     *
     * @param enabled whether the purge job runs at all
     * @param detailDays default age past which a terminated workflow loses its detailed payloads
     * @param batchSize maximum number of workflows purged per project and per run
     */
    public record Retention(Boolean enabled, Integer detailDays, Integer batchSize) {

        public Retention {
            enabled = enabled == null || enabled;
            detailDays = detailDays == null ? 90 : detailDays;
            batchSize = batchSize == null || batchSize <= 0 ? 200 : batchSize;
        }
    }

    /**
     * Capture of the full output of each step.
     *
     * <p>This is the debugging trail: everything the container printed, plus the platform log of the
     * step. It is by far the largest thing the platform stores, so it has a budget per step and a
     * lifetime of its own, both shorter than what the audit payloads get.
     *
     * @param enabled whether steps record their output at all
     * @param maxCharsPerStep budget of one step; past it the middle of the step is dropped and the
     *     head and the tail are kept, because that is where the cause and the failure are
     * @param retentionDays age past which the logs of a finished workflow are deleted, 0 to keep them
     */
    public record Logs(Boolean enabled, Integer maxCharsPerStep, Integer retentionDays) {

        public Logs {
            enabled = enabled == null || enabled;
            // ~20 MB of ASCII build output, which gzips to well under a megabyte in the row.
            maxCharsPerStep = maxCharsPerStep == null || maxCharsPerStep < 0 ? 20_000_000 : maxCharsPerStep;
            retentionDays = retentionDays == null ? 14 : Math.max(retentionDays, 0);
        }

        /** Budget to apply, zero when capture is disabled. */
        public int effectiveMaxChars() {
            return enabled ? maxCharsPerStep : 0;
        }
    }
}
