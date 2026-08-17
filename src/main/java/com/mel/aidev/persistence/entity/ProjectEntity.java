package com.mel.aidev.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import com.mel.aidev.project.ScmProvider;
import java.time.Instant;
import java.util.UUID;

/**
 * A project: the unit that owns a GitLab repository, a Jira project, a sandbox image and the
 * execution configuration its workflows inherit.
 *
 * <p>The configuration stored here is data, not a Spring property override: a project must be able
 * to change how <em>its</em> workflows run without touching the configuration of a shared process.
 */
@Entity
@Table(name = "project")
public class ProjectEntity {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "name", nullable = false, length = 128)
    private String name;

    @Column(name = "description", columnDefinition = "text")
    private String description;

    @jakarta.persistence.Enumerated(jakarta.persistence.EnumType.STRING)
    @Column(name = "scm_provider", nullable = false, length = 16)
    private ScmProvider scmProvider = ScmProvider.GITLAB;

    @Column(name = "gitlab_project", nullable = false, length = 512)
    private String gitlabProject;

    @Column(name = "jira_project_key", length = 32)
    private String jiraProjectKey;

    /** Fixed image; when null the global per-profile configuration applies. */
    @Column(name = "docker_image", length = 512)
    private String dockerImage;

    @Column(name = "default_branch", length = 255)
    private String defaultBranch;

    @Column(name = "branch_prefix", length = 64)
    private String branchPrefix;

    /** Comma-separated; adds to the global list, never replaces it. */
    @Column(name = "protected_branches", columnDefinition = "text")
    private String protectedBranches;

    /** JSON argv array, e.g. {@code ["mvn","-B","-ntp","test-compile"]}. Never a shell string. */
    @Column(name = "build_command", columnDefinition = "text")
    private String buildCommand;

    @Column(name = "test_command", columnDefinition = "text")
    private String testCommand;

    @Column(name = "lint_command", columnDefinition = "text")
    private String lintCommand;

    /** Days before the detailed payloads of a terminated workflow are purged; 0 means never. */
    @Column(name = "retention_days")
    private Integer retentionDays;

    @Column(name = "active", nullable = false)
    private boolean active;

    @Column(name = "archived_at")
    private Instant archivedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    protected ProjectEntity() {
        // for JPA
    }

    public ProjectEntity(UUID id, String name, String gitlabProject) {
        this.id = id;
        this.name = name;
        this.gitlabProject = gitlabProject;
        this.active = true;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public void touch() {
        this.updatedAt = Instant.now();
    }

    /** Archives the project: it stays readable but can no longer start a workflow. */
    public void archive() {
        this.active = false;
        this.archivedAt = Instant.now();
        touch();
    }

    public void restore() {
        this.active = true;
        this.archivedAt = null;
        touch();
    }

    public boolean isArchived() {
        return archivedAt != null;
    }

    /** True when the project may start new workflows. */
    public boolean isStartable() {
        return active && archivedAt == null;
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getGitlabProject() {
        return gitlabProject;
    }

    public ScmProvider getScmProvider() { return scmProvider; }

    public void setScmProvider(ScmProvider scmProvider) { this.scmProvider = scmProvider == null ? ScmProvider.GITLAB : scmProvider; }

    public void setGitlabProject(String gitlabProject) {
        this.gitlabProject = gitlabProject;
    }

    public String getJiraProjectKey() {
        return jiraProjectKey;
    }

    public void setJiraProjectKey(String jiraProjectKey) {
        this.jiraProjectKey = jiraProjectKey;
    }

    public String getDockerImage() {
        return dockerImage;
    }

    public void setDockerImage(String dockerImage) {
        this.dockerImage = dockerImage;
    }

    public String getDefaultBranch() {
        return defaultBranch;
    }

    public void setDefaultBranch(String defaultBranch) {
        this.defaultBranch = defaultBranch;
    }

    public String getBranchPrefix() {
        return branchPrefix;
    }

    public void setBranchPrefix(String branchPrefix) {
        this.branchPrefix = branchPrefix;
    }

    public String getProtectedBranches() {
        return protectedBranches;
    }

    public void setProtectedBranches(String protectedBranches) {
        this.protectedBranches = protectedBranches;
    }

    public String getBuildCommand() {
        return buildCommand;
    }

    public void setBuildCommand(String buildCommand) {
        this.buildCommand = buildCommand;
    }

    public String getTestCommand() {
        return testCommand;
    }

    public void setTestCommand(String testCommand) {
        this.testCommand = testCommand;
    }

    public String getLintCommand() {
        return lintCommand;
    }

    public void setLintCommand(String lintCommand) {
        this.lintCommand = lintCommand;
    }

    public Integer getRetentionDays() {
        return retentionDays;
    }

    public void setRetentionDays(Integer retentionDays) {
        this.retentionDays = retentionDays;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public Instant getArchivedAt() {
        return archivedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public long getVersion() {
        return version;
    }
}
