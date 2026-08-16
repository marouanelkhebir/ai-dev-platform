package com.company.aidev.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** Merge request created by the platform for a workflow. */
@Entity
@Table(name = "merge_request")
public class MergeRequestEntity {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "workflow_id", nullable = false)
    private UUID workflowId;

    @Column(name = "gitlab_project", nullable = false, length = 512)
    private String gitlabProject;

    @Column(name = "iid", nullable = false)
    private long iid;

    @Column(name = "url", length = 1024)
    private String url;

    @Column(name = "source_branch", nullable = false, length = 255)
    private String sourceBranch;

    @Column(name = "target_branch", nullable = false, length = 255)
    private String targetBranch;

    @Column(name = "title", length = 512)
    private String title;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected MergeRequestEntity() {
        // for JPA
    }

    public MergeRequestEntity(
            UUID workflowId,
            String gitlabProject,
            long iid,
            String url,
            String sourceBranch,
            String targetBranch,
            String title) {
        this.id = UUID.randomUUID();
        this.workflowId = workflowId;
        this.gitlabProject = gitlabProject;
        this.iid = iid;
        this.url = url;
        this.sourceBranch = sourceBranch;
        this.targetBranch = targetBranch;
        this.title = title;
        this.createdAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getWorkflowId() {
        return workflowId;
    }

    public String getGitlabProject() {
        return gitlabProject;
    }

    public long getIid() {
        return iid;
    }

    public String getUrl() {
        return url;
    }

    public String getSourceBranch() {
        return sourceBranch;
    }

    public String getTargetBranch() {
        return targetBranch;
    }

    public String getTitle() {
        return title;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
