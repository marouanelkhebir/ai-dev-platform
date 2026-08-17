package com.mel.aidev.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** Result of one local test run (one row per development attempt). */
@Entity
@Table(name = "test_result")
public class TestResultEntity {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "workflow_id", nullable = false)
    private UUID workflowId;

    @Column(name = "attempt", nullable = false)
    private int attempt;

    @Column(name = "successful", nullable = false)
    private boolean successful;

    @Column(name = "total_tests", nullable = false)
    private int totalTests;

    @Column(name = "failed_tests", nullable = false)
    private int failedTests;

    @Column(name = "report_json", columnDefinition = "text")
    private String reportJson;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected TestResultEntity() {
        // for JPA
    }

    public TestResultEntity(
            UUID workflowId, int attempt, boolean successful, int totalTests, int failedTests, String reportJson) {
        this.id = UUID.randomUUID();
        this.workflowId = workflowId;
        this.attempt = attempt;
        this.successful = successful;
        this.totalTests = totalTests;
        this.failedTests = failedTests;
        this.reportJson = reportJson;
        this.createdAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getWorkflowId() {
        return workflowId;
    }

    public int getAttempt() {
        return attempt;
    }

    public boolean isSuccessful() {
        return successful;
    }

    public int getTotalTests() {
        return totalTests;
    }

    public int getFailedTests() {
        return failedTests;
    }

    public String getReportJson() {
        return reportJson;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
