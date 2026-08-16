package com.company.aidev.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** Result of a review pass (code, security or acceptance). */
@Entity
@Table(name = "review_result")
public class ReviewResultEntity {

    /** Kind of review stored in this row. */
    public enum ReviewType {
        CODE,
        SECURITY,
        ACCEPTANCE
    }

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "workflow_id", nullable = false)
    private UUID workflowId;

    @Column(name = "review_type", nullable = false, length = 32)
    private String reviewType;

    @Column(name = "attempt", nullable = false)
    private int attempt;

    @Column(name = "decision", nullable = false, length = 32)
    private String decision;

    @Column(name = "findings_json", columnDefinition = "text")
    private String findingsJson;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected ReviewResultEntity() {
        // for JPA
    }

    public ReviewResultEntity(
            UUID workflowId, ReviewType reviewType, int attempt, String decision, String findingsJson) {
        this.id = UUID.randomUUID();
        this.workflowId = workflowId;
        this.reviewType = reviewType.name();
        this.attempt = attempt;
        this.decision = decision;
        this.findingsJson = findingsJson;
        this.createdAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getWorkflowId() {
        return workflowId;
    }

    public String getReviewType() {
        return reviewType;
    }

    public int getAttempt() {
        return attempt;
    }

    public String getDecision() {
        return decision;
    }

    public String getFindingsJson() {
        return findingsJson;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
