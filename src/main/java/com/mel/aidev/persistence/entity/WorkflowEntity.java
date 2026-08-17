package com.mel.aidev.persistence.entity;

import com.mel.aidev.domain.BuildProfile;
import com.mel.aidev.workflow.WorkflowStatus;
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

    /** Owning project. A workflow always belongs to one. */
    @Column(name = "project_id", nullable = false)
    private UUID projectId;

    @Column(name = "jira_ticket", nullable = false, length = 64)
    private String jiraTicket;

    /** Original request for workflows started without a Jira issue. */
    @Column(name = "source_message", columnDefinition = "text")
    private String sourceMessage;

    /** Information supplied by a human after the analyst requested clarification. */
    @Column(name = "human_clarification", columnDefinition = "text")
    private String humanClarification;

    @Column(name = "gitlab_project", nullable = false, length = 512)
    private String gitlabProject;

    @Column(name = "base_branch", nullable = false, length = 255)
    private String baseBranch;

    @Column(name = "branch", length = 255)
    private String branch;

    @Enumerated(EnumType.STRING)
    @Column(name = "build_profile", length = 32)
    private BuildProfile buildProfile;

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

    /**
     * Snapshot of the project configuration used at launch.
     *
     * <p>Changing the project afterwards must not rewrite the history of a workflow that already
     * ran, so the engine reads this rather than re-resolving — including on retry.
     */
    @Column(name = "launch_config", columnDefinition = "text")
    private String launchConfig;

    /** Image the sandbox was actually started with. Kept queryable for audit. */
    @Column(name = "sandbox_image", length = 512)
    private String sandboxImage;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** Set once when the workflow reaches a terminal status; {@code updatedAt} moves too often to measure anything. */
    @Column(name = "finished_at")
    private Instant finishedAt;

    /** Hidden from the default listings, audit trail intact. */
    @Column(name = "archived_at")
    private Instant archivedAt;

    /** Set by the retention job once the detailed payloads have been replaced by {@link #auditSummary}. */
    @Column(name = "purged_at")
    private Instant purgedAt;

    @Column(name = "audit_summary", columnDefinition = "text")
    private String auditSummary;

    /** Set when the engine claims the workflow, used to detect stale executions after a crash. */
    @Column(name = "claimed_at")
    private Instant claimedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    protected WorkflowEntity() {
        // for JPA
    }

    public WorkflowEntity(UUID id, UUID projectId, String jiraTicket, String gitlabProject, String baseBranch) {
        this.id = id;
        this.projectId = projectId;
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

    public UUID getProjectId() {
        return projectId;
    }

    public String getJiraTicket() {
        return jiraTicket;
    }

    public String getSourceMessage() {
        return sourceMessage;
    }

    public void setSourceMessage(String sourceMessage) {
        this.sourceMessage = sourceMessage;
        touch();
    }

    public String getHumanClarification() {
        return humanClarification;
    }

    public void setHumanClarification(String humanClarification) {
        this.humanClarification = humanClarification;
        touch();
    }

    public boolean isJiraBacked() {
        return sourceMessage == null;
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

    public BuildProfile getBuildProfile() {
        return buildProfile;
    }

    public void setBuildProfile(BuildProfile buildProfile) {
        this.buildProfile = buildProfile;
    }

    public WorkflowStatus getStatus() {
        return status;
    }

    public void setStatus(WorkflowStatus status) {
        this.status = status;
        // Cleared when the workflow becomes runnable again: a retried workflow has not finished, and
        // the duration reported for it must be the one of the round that actually terminated.
        this.finishedAt = status.isTerminal() ? Instant.now() : null;
        touch();
    }

    public int getDevelopmentAttempts() {
        return developmentAttempts;
    }

    public int incrementDevelopmentAttempts() {
        return ++developmentAttempts;
    }

    /**
     * Starts a fresh implementation budget after a review has requested changes.
     *
     * <p>Review rounds have their own bounded counter. A review must therefore be able to send work
     * back to the developer even when the preceding implementation cycle used its full budget.
     */
    public void resetDevelopmentAttempts() {
        this.developmentAttempts = 0;
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

    public String getLaunchConfig() {
        return launchConfig;
    }

    public void setLaunchConfig(String launchConfig) {
        this.launchConfig = launchConfig;
    }

    public String getSandboxImage() {
        return sandboxImage;
    }

    public void setSandboxImage(String sandboxImage) {
        this.sandboxImage = sandboxImage;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }

    public Instant getArchivedAt() {
        return archivedAt;
    }

    public boolean isArchived() {
        return archivedAt != null;
    }

    public void archive() {
        this.archivedAt = Instant.now();
        touch();
    }

    public void restore() {
        this.archivedAt = null;
        touch();
    }

    public Instant getPurgedAt() {
        return purgedAt;
    }

    public String getAuditSummary() {
        return auditSummary;
    }

    /**
     * Replaces the detailed payloads by a compact summary.
     *
     * <p>Called by the retention job only: the summary is written before anything is deleted, so an
     * interrupted purge never loses the trace without leaving the marker behind.
     */
    public void purgeDetails(String summary) {
        this.auditSummary = summary;
        this.purgedAt = Instant.now();
        this.ticketAnalysisJson = null;
        this.technicalPlanJson = null;
        this.testReportJson = null;
        this.codeReviewJson = null;
        this.securityReportJson = null;
        this.acceptanceReportJson = null;
        this.pendingFeedback = null;
        touch();
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
