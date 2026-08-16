package com.company.aidev.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** One HTTP round trip to the configured LLM API, recorded for cost and latency accounting. */
@Entity
@Table(name = "llm_execution")
public class LlmExecutionEntity {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "workflow_id")
    private UUID workflowId;

    @Column(name = "agent", length = 64)
    private String agent;

    @Column(name = "model", length = 128)
    private String model;

    @Column(name = "prompt_tokens")
    private Integer promptTokens;

    @Column(name = "completion_tokens")
    private Integer completionTokens;

    @Column(name = "total_tokens")
    private Integer totalTokens;

    @Column(name = "duration_ms")
    private Long durationMs;

    @Column(name = "successful", nullable = false)
    private boolean successful;

    @Column(name = "finish_reason", length = 64)
    private String finishReason;

    @Column(name = "error", columnDefinition = "text")
    private String error;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected LlmExecutionEntity() {
        // for JPA
    }

    private LlmExecutionEntity(UUID workflowId, String agent, String model, long durationMs, boolean successful) {
        this.id = UUID.randomUUID();
        this.workflowId = workflowId;
        this.agent = agent;
        this.model = model;
        this.durationMs = durationMs;
        this.successful = successful;
        this.createdAt = Instant.now();
    }

    public static LlmExecutionEntity success(
            UUID workflowId,
            String agent,
            String model,
            long durationMs,
            Integer promptTokens,
            Integer completionTokens,
            Integer totalTokens,
            String finishReason) {
        LlmExecutionEntity entity = new LlmExecutionEntity(workflowId, agent, model, durationMs, true);
        entity.promptTokens = promptTokens;
        entity.completionTokens = completionTokens;
        entity.totalTokens = totalTokens;
        entity.finishReason = finishReason;
        return entity;
    }

    public static LlmExecutionEntity failure(
            UUID workflowId, String agent, String model, long durationMs, String error) {
        LlmExecutionEntity entity = new LlmExecutionEntity(workflowId, agent, model, durationMs, false);
        entity.error = error;
        return entity;
    }

    public UUID getId() {
        return id;
    }

    public UUID getWorkflowId() {
        return workflowId;
    }

    public String getAgent() {
        return agent;
    }

    public String getModel() {
        return model;
    }

    public Integer getPromptTokens() {
        return promptTokens;
    }

    public Integer getCompletionTokens() {
        return completionTokens;
    }

    public Integer getTotalTokens() {
        return totalTokens;
    }

    public Long getDurationMs() {
        return durationMs;
    }

    public boolean isSuccessful() {
        return successful;
    }

    public String getFinishReason() {
        return finishReason;
    }

    public String getError() {
        return error;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
