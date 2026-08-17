package com.mel.aidev.persistence.entity;

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

    /**
     * Owning project, denormalised on purpose.
     *
     * <p>This table has no foreign key to {@code workflow}, which is what lets the cost history of a
     * project survive both the deletion of a workflow and the purge of its details.
     */
    @Column(name = "project_id")
    private UUID projectId;

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

    /**
     * Cost of the call, frozen at the tariff in force when the row was written.
     *
     * <p>Null when the model has no tariff. Recomputing later would rewrite past months every time a
     * price changes.
     */
    @Column(name = "cost_micros")
    private Long costMicros;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected LlmExecutionEntity() {
        // for JPA
    }

    private LlmExecutionEntity(
            UUID workflowId, UUID projectId, String agent, String model, long durationMs, boolean successful) {
        this.id = UUID.randomUUID();
        this.workflowId = workflowId;
        this.projectId = projectId;
        this.agent = agent;
        this.model = model;
        this.durationMs = durationMs;
        this.successful = successful;
        this.createdAt = Instant.now();
    }

    public static LlmExecutionEntity success(
            UUID workflowId,
            UUID projectId,
            String agent,
            String model,
            long durationMs,
            Integer promptTokens,
            Integer completionTokens,
            Integer totalTokens,
            String finishReason,
            Long costMicros) {
        LlmExecutionEntity entity = new LlmExecutionEntity(workflowId, projectId, agent, model, durationMs, true);
        entity.promptTokens = promptTokens;
        entity.completionTokens = completionTokens;
        entity.totalTokens = totalTokens;
        entity.finishReason = finishReason;
        entity.costMicros = costMicros;
        return entity;
    }

    public static LlmExecutionEntity failure(
            UUID workflowId, UUID projectId, String agent, String model, long durationMs, String error) {
        LlmExecutionEntity entity = new LlmExecutionEntity(workflowId, projectId, agent, model, durationMs, false);
        entity.error = error;
        return entity;
    }

    public UUID getId() {
        return id;
    }

    public UUID getWorkflowId() {
        return workflowId;
    }

    public UUID getProjectId() {
        return projectId;
    }

    public Long getCostMicros() {
        return costMicros;
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
