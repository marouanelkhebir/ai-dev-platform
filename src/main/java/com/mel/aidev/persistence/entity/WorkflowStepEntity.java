package com.mel.aidev.persistence.entity;

import com.mel.aidev.workflow.WorkflowStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/** One executed transition of the state machine. This is the timeline of a ticket. */
@Entity
@Table(name = "workflow_step")
public class WorkflowStepEntity {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "workflow_id", nullable = false)
    private UUID workflowId;

    @Column(name = "sequence_number", nullable = false)
    private int sequenceNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "status_from", nullable = false, length = 64)
    private WorkflowStatus statusFrom;

    @Enumerated(EnumType.STRING)
    @Column(name = "status_to", length = 64)
    private WorkflowStatus statusTo;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(name = "duration_ms")
    private Long durationMs;

    @Column(name = "successful")
    private Boolean successful;

    @Column(name = "detail", columnDefinition = "text")
    private String detail;

    @Column(name = "error", columnDefinition = "text")
    private String error;

    protected WorkflowStepEntity() {
        // for JPA
    }

    public WorkflowStepEntity(UUID workflowId, int sequenceNumber, WorkflowStatus statusFrom) {
        this.id = UUID.randomUUID();
        this.workflowId = workflowId;
        this.sequenceNumber = sequenceNumber;
        this.statusFrom = statusFrom;
        this.startedAt = Instant.now();
    }

    public void complete(WorkflowStatus statusTo, boolean successful, String detail, String error) {
        this.statusTo = statusTo;
        this.successful = successful;
        this.detail = detail;
        this.error = error;
        this.finishedAt = Instant.now();
        this.durationMs = Duration.between(startedAt, finishedAt).toMillis();
    }

    /**
     * Drops the free-text payloads while keeping the transition itself.
     *
     * <p>Called by the retention job: the shape of the run — which transitions happened, how long
     * each took, whether it succeeded — is what makes the trail useful long after the details of a
     * step stopped mattering.
     */
    public void purgeDetails() {
        this.detail = null;
        this.error = null;
    }

    public UUID getId() {
        return id;
    }

    public UUID getWorkflowId() {
        return workflowId;
    }

    public int getSequenceNumber() {
        return sequenceNumber;
    }

    public WorkflowStatus getStatusFrom() {
        return statusFrom;
    }

    public WorkflowStatus getStatusTo() {
        return statusTo;
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

    public String getDetail() {
        return detail;
    }

    public String getError() {
        return error;
    }
}
