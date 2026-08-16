package com.company.aidev.persistence.entity;

import com.company.aidev.workflow.WorkflowStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

/**
 * The persisted development workflow. This row is the single source of truth: agents, sandboxes and
 * webhooks are all stateless with respect to it.
 */
@Entity
@Table(name = "workflow")
public class WorkflowEntity {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "jira_ticket", nullable = false, length = 64)
    private String jiraTicket;

    @Column(name = "gitlab_project", nullable = false, length = 512)
    private String gitlabProject;

    @Column(name = "base_branch", nullable = false, length = 255)
    private String baseBranch;

    @Column(name = "branch", length = 255)
    private String branch;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 64)
    private WorkflowStatus status;

    @Column(name = "development_attempts", nullable = false)
    private int developmentAttempts;

    @Column(name = "pipeline_attempts", nullable = false)
    private int pipelineAttempts;

    @Column(name = "review_attempts", nullable = false)
    private int reviewAttempts;

    @Column(name = "merge_request_iid")
    private Long mergeRequestIid;

    @Column(name = "merge_request_url", length = 1024)
    private String mergeRequestUrl;

    @Column(name = "pipeline_id")
    private Long pipelineId;

    @Column(name = "commit_sha", length = 64)
    private String commitSha;

    @Column(name = "failure_reason", columnDefinition = "text")
    private String failureReason;

    /** Feedback injected into the next developer attempt (test output, review findings, ...). */
    @Column(name = "pending_feedback", columnDefinition = "text")
    private String pendingFeedback;

    @Column(name = "ticket_analysis", columnDefinition = "text")
    private String ticketAnalysisJson;

    @Column(name = "technical_plan", columnDefinition = "text")
    private String technicalPlanJson;

    @Column(name = "test_report", columnDefinition = "text")
    private String testReportJson;

    @Column(name = "code_review", columnDefinition = "text")
    private String codeReviewJson;

    @Column(name = "security_report", columnDefinition = "text")
    private String securityReportJson;

    @Column(name = "acceptance_report", columnDefinition = "text")
    private String acceptanceReportJson;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** Set when the engine claims the workflow, used to detect stale executions after a crash. */
    @Column(name = "claimed_at")
    private Instant claimedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    protected WorkflowEntity() {
        // for JPA
    }

    public WorkflowEntity(UUID id, String jiraTicket, String gitlabProject, String baseBranch) {
        this.id = id;
        this.jiraTicket = jiraTicket;
        this.gitlabProject = gitlabProject;
        this.baseBranch = baseBranch;
        this.status = WorkflowStatus.CREATED;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public void touch() {
        this.updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public String getJiraTicket() {
        return jiraTicket;
    }

    public String getGitlabProject() {
        return gitlabProject;
    }

    public String getBaseBranch() {
        return baseBranch;
    }

    public void setBaseBranch(String baseBranch) {
        this.baseBranch = baseBranch;
    }

    public String getBranch() {
        return branch;
    }

    public void setBranch(String branch) {
        this.branch = branch;
    }

    public WorkflowStatus getStatus() {
        return status;
    }

    public void setStatus(WorkflowStatus status) {
        this.status = status;
        touch();
    }

    public int getDevelopmentAttempts() {
        return developmentAttempts;
    }

    public int incrementDevelopmentAttempts() {
        return ++developmentAttempts;
    }

    public int getPipelineAttempts() {
        return pipelineAttempts;
    }

    public int incrementPipelineAttempts() {
        return ++pipelineAttempts;
    }

    public int getReviewAttempts() {
        return reviewAttempts;
    }

    public int incrementReviewAttempts() {
        return ++reviewAttempts;
    }

    /** Gives the workflow a fresh budget, used when a human explicitly retries it. */
    public void resetAttempts() {
        this.developmentAttempts = 0;
        this.pipelineAttempts = 0;
        this.reviewAttempts = 0;
    }

    public Long getMergeRequestIid() {
        return mergeRequestIid;
    }

    public void setMergeRequestIid(Long mergeRequestIid) {
        this.mergeRequestIid = mergeRequestIid;
    }

    public String getMergeRequestUrl() {
        return mergeRequestUrl;
    }

    public void setMergeRequestUrl(String mergeRequestUrl) {
        this.mergeRequestUrl = mergeRequestUrl;
    }

    public Long getPipelineId() {
        return pipelineId;
    }

    public void setPipelineId(Long pipelineId) {
        this.pipelineId = pipelineId;
    }

    public String getCommitSha() {
        return commitSha;
    }

    public void setCommitSha(String commitSha) {
        this.commitSha = commitSha;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public void setFailureReason(String failureReason) {
        this.failureReason = failureReason;
    }

    public String getPendingFeedback() {
        return pendingFeedback;
    }

    public void setPendingFeedback(String pendingFeedback) {
        this.pendingFeedback = pendingFeedback;
    }

    public String getTicketAnalysisJson() {
        return ticketAnalysisJson;
    }

    public void setTicketAnalysisJson(String ticketAnalysisJson) {
        this.ticketAnalysisJson = ticketAnalysisJson;
    }

    public String getTechnicalPlanJson() {
        return technicalPlanJson;
    }

    public void setTechnicalPlanJson(String technicalPlanJson) {
        this.technicalPlanJson = technicalPlanJson;
    }

    public String getTestReportJson() {
        return testReportJson;
    }

    public void setTestReportJson(String testReportJson) {
        this.testReportJson = testReportJson;
    }

    public String getCodeReviewJson() {
        return codeReviewJson;
    }

    public void setCodeReviewJson(String codeReviewJson) {
        this.codeReviewJson = codeReviewJson;
    }

    public String getSecurityReportJson() {
        return securityReportJson;
    }

    public void setSecurityReportJson(String securityReportJson) {
        this.securityReportJson = securityReportJson;
    }

    public String getAcceptanceReportJson() {
        return acceptanceReportJson;
    }

    public void setAcceptanceReportJson(String acceptanceReportJson) {
        this.acceptanceReportJson = acceptanceReportJson;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Instant getClaimedAt() {
        return claimedAt;
    }

    public void setClaimedAt(Instant claimedAt) {
        this.claimedAt = claimedAt;
    }

    public long getVersion() {
        return version;
    }
}
