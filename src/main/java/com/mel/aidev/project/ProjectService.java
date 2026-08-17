package com.mel.aidev.project;

import com.mel.aidev.llm.ModelRole;
import com.mel.aidev.persistence.entity.ProjectEntity;
import com.mel.aidev.persistence.entity.ProjectModelEntity;
import com.mel.aidev.persistence.entity.ProjectVariableEntity;
import com.mel.aidev.persistence.repository.ProjectModelRepository;
import com.mel.aidev.persistence.repository.ProjectRepository;
import com.mel.aidev.persistence.repository.ProjectVariableRepository;
import com.mel.aidev.persistence.repository.WorkflowRepository;
import com.mel.aidev.gitlab.ScmProjectId;
import com.mel.aidev.workflow.WorkflowStatus;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Application service of the projects.
 *
 * <p>A project owns its repository, its Jira key, its image and its execution configuration; a
 * workflow inherits all of it. Everything that creates, edits, archives, clones or deletes one goes
 * through here so the validations cannot be bypassed by a second entry point.
 */
@Service
public class ProjectService {

    private static final Logger log = LoggerFactory.getLogger(ProjectService.class);

    private static final EnumSet<WorkflowStatus> TERMINAL_STATUSES = EnumSet.of(
            WorkflowStatus.DONE,
            WorkflowStatus.FAILED,
            WorkflowStatus.CANCELLED,
            WorkflowStatus.NEEDS_CLARIFICATION);

    private final ProjectRepository projectRepository;
    private final ProjectVariableRepository variableRepository;
    private final ProjectModelRepository modelRepository;
    private final WorkflowRepository workflowRepository;
    private final ProjectValidator validator;
    private final ProjectConfigurationResolver resolver;
    private final ArgvCodec argvCodec;

    public ProjectService(
            ProjectRepository projectRepository,
            ProjectVariableRepository variableRepository,
            ProjectModelRepository modelRepository,
            WorkflowRepository workflowRepository,
            ProjectValidator validator,
            ProjectConfigurationResolver resolver,
            ArgvCodec argvCodec) {
        this.projectRepository = projectRepository;
        this.variableRepository = variableRepository;
        this.modelRepository = modelRepository;
        this.workflowRepository = workflowRepository;
        this.validator = validator;
        this.resolver = resolver;
        this.argvCodec = argvCodec;
    }

    // ---------------------------------------------------------------------- read

    @Transactional(readOnly = true)
    public ProjectEntity get(UUID projectId) {
        return projectRepository.findById(projectId).orElseThrow(() -> new ProjectNotFoundException(projectId));
    }

    @Transactional(readOnly = true)
    public Page<ProjectEntity> search(String query, boolean activeOnly, Pageable pageable) {
        return projectRepository.search(SearchPattern.contains(query), activeOnly, pageable);
    }

    @Transactional(readOnly = true)
    public ProjectConfiguration configurationOf(UUID projectId) {
        return resolver.resolve(get(projectId));
    }

    @Transactional(readOnly = true)
    public long countWorkflows(UUID projectId) {
        return workflowRepository.countByProjectId(projectId);
    }

    @Transactional(readOnly = true)
    public long countActiveWorkflows(UUID projectId) {
        return workflowRepository.countActiveByProjectId(projectId, TERMINAL_STATUSES);
    }

    /**
     * Resolves the single project that may start work on a GitLab repository.
     *
     * <p>The repository is not unique across projects, so the caller has to be able to tell "none"
     * from "several"; both answers need a different message and neither may pick one at random.
     */
    @Transactional(readOnly = true)
    public List<ProjectEntity> findStartableByGitlabProject(String gitlabProject) {
        return gitlabProject == null || gitlabProject.isBlank()
                ? List.of()
                : projectRepository.findStartableByGitlabProject(gitlabProject.trim());
    }

    @Transactional(readOnly = true)
    public List<ProjectEntity> findStartableByJiraProjectKey(String jiraProjectKey) {
        return jiraProjectKey == null || jiraProjectKey.isBlank()
                ? List.of()
                : projectRepository.findStartableByJiraProjectKey(jiraProjectKey.trim());
    }

    // --------------------------------------------------------------------- write

    @Transactional
    public ProjectEntity create(ProjectDefinition definition) {
        validator.validateName(definition.name());
        assertNameAvailable(definition.name(), null);

        ProjectEntity project =
                new ProjectEntity(UUID.randomUUID(), definition.name().trim(), definition.gitlabProject().trim());
        apply(project, definition);
        ProjectEntity saved = projectRepository.save(project);
        replaceVariables(saved.getId(), definition.variables());
        replaceModels(saved.getId(), definition.models());

        log.info(
                "Project {} created on repository {} (Jira {})",
                saved.getName(),
                saved.getGitlabProject(),
                saved.getJiraProjectKey());
        return saved;
    }

    @Transactional
    public ProjectEntity update(UUID projectId, ProjectDefinition definition) {
        ProjectEntity project = get(projectId);
        validator.validateName(definition.name());
        assertNameAvailable(definition.name(), projectId);

        project.setName(definition.name().trim());
        apply(project, definition);
        ProjectEntity saved = projectRepository.save(project);
        replaceVariables(projectId, definition.variables());
        replaceModels(projectId, definition.models());

        log.info("Project {} updated", saved.getName());
        return saved;
    }

    /**
     * Copies a project under a new name.
     *
     * <p>Configuration, variables and pinned models are copied; workflows never are. The clone goes
     * through the full validation rather than inheriting the validity of its source: the overridden
     * fields may point elsewhere, and the registry allowlist may have changed since.
     */
    @Transactional
    public ProjectEntity clone(UUID sourceId, String name, String gitlabProject, String jiraProjectKey, String dockerImage) {
        ProjectEntity source = get(sourceId);
        ProjectDefinition definition = new ProjectDefinition(
                name,
                source.getDescription(),
                source.getScmProvider(),
                orDefault(gitlabProject, ScmProjectId.repository(source.getGitlabProject())),
                orDefault(jiraProjectKey, source.getJiraProjectKey()),
                orDefault(dockerImage, source.getDockerImage()),
                source.getDefaultBranch(),
                source.getBranchPrefix(),
                splitCsv(source.getProtectedBranches()),
                argvCodec.read(source.getBuildCommand()),
                argvCodec.read(source.getTestCommand()),
                argvCodec.read(source.getLintCommand()),
                source.getRetentionDays(),
                resolver.variables(sourceId),
                resolver.pinnedModels(sourceId),
                // Cloning an archived project produces an archived clone: reactivating is an explicit
                // decision, not a side effect of duplicating a configuration.
                source.isStartable());

        ProjectEntity clone = create(definition);
        if (!source.isStartable()) {
            clone.archive();
            projectRepository.save(clone);
        }
        log.info("Project {} cloned from {}", clone.getName(), source.getName());
        return clone;
    }

    /** Archives the project: readable, no longer startable, workflows and audit untouched. */
    @Transactional
    public ProjectEntity archive(UUID projectId) {
        ProjectEntity project = get(projectId);
        long active = countActiveWorkflows(projectId);
        if (active > 0) {
            throw new IllegalStateException(
                    "Project " + project.getName() + " still has " + active + " running workflow(s); cancel them first");
        }
        project.archive();
        log.info("Project {} archived", project.getName());
        return projectRepository.save(project);
    }

    @Transactional
    public ProjectEntity restore(UUID projectId) {
        ProjectEntity project = get(projectId);
        // The repository, the key and the image may have become invalid while the project slept.
        validator.validateRepository(project.getScmProvider(), project.getGitlabProject());
        validator.validateJiraProjectKey(project.getJiraProjectKey());
        validator.validateImage(project.getDockerImage());
        project.restore();
        log.info("Project {} restored", project.getName());
        return projectRepository.save(project);
    }

    /**
     * Deletes a project for good.
     *
     * <p>Only possible once it holds no workflow at all: deleting one that does would take its audit
     * trail with it. Archiving is what the screen offers by default.
     */
    @Transactional
    public void delete(UUID projectId) {
        ProjectEntity project = get(projectId);
        long workflows = countWorkflows(projectId);
        if (workflows > 0) {
            throw new IllegalStateException("Project " + project.getName() + " still holds " + workflows
                    + " workflow(s); archive it, or delete its workflows first");
        }
        modelRepository.deleteByProjectId(projectId);
        variableRepository.deleteAll(variableRepository.findByProjectIdOrderByNameAsc(projectId));
        projectRepository.delete(project);
        log.info("Project {} deleted", project.getName());
    }

    // ----------------------------------------------------------------- variables

    @Transactional
    public void putVariable(UUID projectId, String name, String value) {
        get(projectId);
        validator.validateVariable(name, value);
        Optional<ProjectVariableEntity> existing = variableRepository.findByProjectIdAndName(projectId, name);
        if (existing.isPresent()) {
            existing.get().setValue(value);
            variableRepository.save(existing.get());
            return;
        }
        variableRepository.save(new ProjectVariableEntity(projectId, name, value));
    }

    @Transactional
    public void deleteVariable(UUID projectId, String name) {
        get(projectId);
        variableRepository.deleteByProjectIdAndName(projectId, name);
    }

    // ----------------------------------------------------------------- internals

    private void apply(ProjectEntity project, ProjectDefinition definition) {
        validator.validateRepository(definition.scmProvider(), definition.gitlabProject());
        validator.validateJiraProjectKey(definition.jiraProjectKey());
        validator.validateImage(definition.dockerImage());
        validator.validateBranchPrefix(definition.branchPrefix());
        validator.validateCommand("build", definition.buildCommand());
        validator.validateCommand("test", definition.testCommand());
        validator.validateCommand("lint", definition.lintCommand());
        validator.validateModels(definition.models());
        for (Map.Entry<String, String> variable : definition.variables().entrySet()) {
            validator.validateVariable(variable.getKey(), variable.getValue());
        }
        if (definition.retentionDays() != null && definition.retentionDays() < 0) {
            throw new ProjectValidationException("The retention must be a number of days, or 0 to keep the details forever");
        }

        project.setDescription(trimToNull(definition.description()));
        project.setScmProvider(definition.scmProvider());
        project.setGitlabProject(definition.scmProvider() == ScmProvider.BITBUCKET
                ? ScmProjectId.bitbucket(definition.gitlabProject())
                : definition.gitlabProject().trim());
        project.setJiraProjectKey(
                definition.jiraProjectKey() == null || definition.jiraProjectKey().isBlank()
                        ? null
                        : definition.jiraProjectKey().trim().toUpperCase(Locale.ROOT));
        project.setDockerImage(trimToNull(definition.dockerImage()));
        // Left null when unset: the resolver then asks GitLab, so the project follows a repository
        // that renames its default branch instead of pinning a stale copy of it.
        project.setDefaultBranch(trimToNull(definition.defaultBranch()));
        project.setBranchPrefix(trimToNull(definition.branchPrefix()));
        project.setProtectedBranches(
                definition.protectedBranches().isEmpty() ? null : String.join(",", definition.protectedBranches()));
        project.setBuildCommand(argvCodec.write(definition.buildCommand()));
        project.setTestCommand(argvCodec.write(definition.testCommand()));
        project.setLintCommand(argvCodec.write(definition.lintCommand()));
        project.setRetentionDays(definition.retentionDays());
        if (definition.active() != null && !project.isArchived()) {
            project.setActive(definition.active());
        }
        project.touch();
    }

    private void assertNameAvailable(String name, UUID selfId) {
        projectRepository.findByNameIgnoreCase(name.trim()).ifPresent(existing -> {
            if (!existing.getId().equals(selfId)) {
                throw new ProjectValidationException("A project named '" + existing.getName() + "' already exists");
            }
        });
    }

    private void replaceVariables(UUID projectId, Map<String, String> variables) {
        List<ProjectVariableEntity> existing = variableRepository.findByProjectIdOrderByNameAsc(projectId);
        variableRepository.deleteAll(existing);
        variableRepository.flush();
        for (Map.Entry<String, String> entry : variables.entrySet()) {
            variableRepository.save(new ProjectVariableEntity(projectId, entry.getKey(), entry.getValue()));
        }
    }

    private void replaceModels(UUID projectId, Map<ModelRole, String> models) {
        modelRepository.deleteByProjectId(projectId);
        modelRepository.flush();
        for (Map.Entry<ModelRole, String> entry : models.entrySet()) {
            if (entry.getValue() != null && !entry.getValue().isBlank()) {
                modelRepository.save(new ProjectModelEntity(projectId, entry.getKey(), entry.getValue().trim()));
            }
        }
    }

    private static List<String> splitCsv(String csv) {
        if (csv == null || csv.isBlank()) {
            return List.of();
        }
        return List.of(csv.split(",")).stream().map(String::trim).filter(value -> !value.isEmpty()).toList();
    }

    private static String orDefault(String override, String fallback) {
        return override == null || override.isBlank() ? fallback : override;
    }

    private static String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
