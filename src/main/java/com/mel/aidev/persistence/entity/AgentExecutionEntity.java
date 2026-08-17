package com.mel.aidev.persistence.entity;

import com.mel.aidev.agent.AgentType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * One agent invocation, with the prompt actually sent and the structured output produced.
 *
 * <p>Prompts and outputs are redacted before being stored so that a leaked token never lands in the
 * audit trail.
 */
@Entity
@Table(name = "agent_execution")
public class AgentExecutionEntity {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "workflow_id", nullable = false)
    private UUID workflowId;

    @Enumerated(EnumType.STRING)
    @Column(name = "agent", nullable = false, length = 64)
    private AgentType agent;

    @Column(name = "model", length = 128)
    private String model;

    @Column(name = "attempt", nullable = false)
    private int attempt;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(name = "duration_ms")
    private Long durationMs;

    @Column(name = "successful")
    private Boolean successful;

    @Column(name = "system_prompt", columnDefinition = "text")
    private String systemPrompt;

    @Column(name = "user_prompt", columnDefinition = "text")
    private String userPrompt;

    @Column(name = "raw_output", columnDefinition = "text")
    private String rawOutput;

    @Column(name = "parsed_output", columnDefinition = "text")
    private String parsedOutput;

    @Column(name = "error", columnDefinition = "text")
    private String error;

    protected AgentExecutionEntity() {
        // for JPA
    }

    public AgentExecutionEntity(UUID workflowId, AgentType agent, String model, int attempt) {
        this.id = UUID.randomUUID();
        this.workflowId = workflowId;
        this.agent = agent;
        this.model = model;
        this.attempt = attempt;
        this.startedAt = Instant.now();
    }

    public void succeeded(String rawOutput, String parsedOutput) {
        this.successful = true;
        this.rawOutput = rawOutput;
        this.parsedOutput = parsedOutput;
        finish();
    }

    public void failed(String error, String rawOutput) {
        this.successful = false;
        this.error = error;
        this.rawOutput = rawOutput;
        finish();
    }

    private void finish() {
        this.finishedAt = Instant.now();
        this.durationMs = Duration.between(startedAt, finishedAt).toMillis();
    }

    public void setPrompts(String systemPrompt, String userPrompt) {
        this.systemPrompt = systemPrompt;
        this.userPrompt = userPrompt;
    }

    public Duration duration() {
        return durationMs == null ? Duration.ZERO : Duration.ofMillis(durationMs);
    }

    /**
     * Drops the prompts and the model output, keeping agent, model, attempt, duration and outcome.
     *
     * <p>These fields hold source code of the repository, which is the real reason they expire.
     */
    public void purgePayloads() {
        this.systemPrompt = null;
        this.userPrompt = null;
        this.rawOutput = null;
        this.parsedOutput = null;
    }

    public UUID getId() {
        return id;
    }

    public UUID getWorkflowId() {
        return workflowId;
    }

    public AgentType getAgent() {
        return agent;
    }

    public String getModel() {
        return model;
    }

    public int getAttempt() {
        return attempt;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }

    public Long getDurationMs() {
        return durationMs;
    }

    public Boolean getSuccessful() {
        return successful;
    }

    public String getSystemPrompt() {
        return systemPrompt;
    }

    public String getUserPrompt() {
        return userPrompt;
    }

    public String getRawOutput() {
        return rawOutput;
    }

    public String getParsedOutput() {
        return parsedOutput;
    }

    public String getError() {
        return error;
    }
}
