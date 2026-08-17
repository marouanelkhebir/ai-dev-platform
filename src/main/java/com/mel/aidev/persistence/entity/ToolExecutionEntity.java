package com.mel.aidev.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** One tool call made by an agent. Every side effect of the platform is traceable through this table. */
@Entity
@Table(name = "tool_execution")
public class ToolExecutionEntity {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "workflow_id", nullable = false)
    private UUID workflowId;

    @Column(name = "agent_execution_id")
    private UUID agentExecutionId;

    @Column(name = "tool_name", nullable = false, length = 128)
    private String toolName;

    @Column(name = "arguments", columnDefinition = "text")
    private String arguments;

    @Column(name = "result", columnDefinition = "text")
    private String result;

    @Column(name = "successful", nullable = false)
    private boolean successful;

    @Column(name = "duration_ms")
    private Long durationMs;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected ToolExecutionEntity() {
        // for JPA
    }

    public ToolExecutionEntity(
            UUID workflowId,
            UUID agentExecutionId,
            String toolName,
            String arguments,
            String result,
            boolean successful,
            long durationMs) {
        this.id = UUID.randomUUID();
        this.workflowId = workflowId;
        this.agentExecutionId = agentExecutionId;
        this.toolName = toolName;
        this.arguments = arguments;
        this.result = result;
        this.successful = successful;
        this.durationMs = durationMs;
        this.createdAt = Instant.now();
    }

    /** Drops the arguments and the output, keeping which tool ran, for how long and with what result. */
    public void purgePayloads() {
        this.arguments = null;
        this.result = null;
    }

    public UUID getId() {
        return id;
    }

    public UUID getWorkflowId() {
        return workflowId;
    }

    public UUID getAgentExecutionId() {
        return agentExecutionId;
    }

    public String getToolName() {
        return toolName;
    }

    public String getArguments() {
        return arguments;
    }

    public String getResult() {
        return result;
    }

    public boolean isSuccessful() {
        return successful;
    }

    public Long getDurationMs() {
        return durationMs;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
