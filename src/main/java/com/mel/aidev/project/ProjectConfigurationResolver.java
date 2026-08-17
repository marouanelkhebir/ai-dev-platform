package com.mel.aidev.project;

import com.mel.aidev.gitlab.GitLabClient;
import com.mel.aidev.gitlab.GitLabException;
import com.mel.aidev.llm.ModelRole;
import com.mel.aidev.persistence.entity.ProjectEntity;
import com.mel.aidev.persistence.entity.ProjectModelEntity;
import com.mel.aidev.persistence.entity.ProjectVariableEntity;
import com.mel.aidev.persistence.repository.ProjectModelRepository;
import com.mel.aidev.persistence.repository.ProjectVariableRepository;
import com.mel.aidev.settings.PlatformSettings;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Resolves the configuration a workflow will run with: project values over global ones.
 *
 * <p>One rule for every field — the project wins, otherwise the platform configuration applies, and
 * in no case does an agent decide. The result is frozen onto the workflow by the caller, so this
 * class is only ever called once per workflow.
 */
@Component
public class ProjectConfigurationResolver {

    private static final Logger log = LoggerFactory.getLogger(ProjectConfigurationResolver.class);

    private final ProjectVariableRepository variableRepository;
    private final ProjectModelRepository modelRepository;
    private final ArgvCodec argvCodec;
    private final GitLabClient gitLabClient;
    private final PlatformSettings settings;

    public ProjectConfigurationResolver(
            ProjectVariableRepository variableRepository,
            ProjectModelRepository modelRepository,
            ArgvCodec argvCodec,
            GitLabClient gitLabClient,
            PlatformSettings settings) {
        this.variableRepository = variableRepository;
        this.modelRepository = modelRepository;
        this.argvCodec = argvCodec;
        this.gitLabClient = gitLabClient;
        this.settings = settings;
    }

    @Transactional(readOnly = true)
    public ProjectConfiguration resolve(ProjectEntity project) {
        return new ProjectConfiguration(
                project.getId(),
                project.getName(),
                project.getGitlabProject(),
                project.getJiraProjectKey(),
                // Null means "no image pinned by the project": the sandbox then falls back to the
                // global per-profile image, which is only known once the build profile is detected.
                blankToNull(project.getDockerImage()),
                resolveBaseBranch(project),
                resolveBranchPrefix(project),
                resolveProtectedBranches(project),
                argvCodec.read(project.getBuildCommand()),
                argvCodec.read(project.getTestCommand()),
                argvCodec.read(project.getLintCommand()),
                resolveVariables(project),
                resolveModels(project));
    }

    private String resolveBaseBranch(ProjectEntity project) {
        if (project.getDefaultBranch() != null && !project.getDefaultBranch().isBlank()) {
            return project.getDefaultBranch().trim();
        }
        try {
            String remoteDefault = gitLabClient.getProject(project.getGitlabProject()).defaultBranch();
            if (remoteDefault != null && !remoteDefault.isBlank()) {
                return remoteDefault;
            }
        } catch (GitLabException e) {
            // Resolution happens when a workflow is created; refusing to create it because GitLab
            // hiccuped would be worse than starting on the configured default branch, which the
            // first engine step verifies anyway.
            log.warn("Unable to read the default branch of {}: {}", project.getGitlabProject(), e.toString());
        }
        return settings.gitlab().defaultTargetBranch();
    }

    private String resolveBranchPrefix(ProjectEntity project) {
        String prefix = project.getBranchPrefix();
        return prefix == null || prefix.isBlank() ? settings.gitlab().branchPrefix() : prefix.trim();
    }

    /**
     * Union of the global protected branches and the ones the project adds.
     *
     * <p>Union, never replacement: the global list is a floor. A project may protect more, never
     * less, otherwise the setting would be a way to unprotect {@code main} from a project form.
     */
    private List<String> resolveProtectedBranches(ProjectEntity project) {
        Set<String> branches = new LinkedHashSet<>(settings.gitlab().protectedBranches());
        if (project.getProtectedBranches() != null) {
            for (String branch : project.getProtectedBranches().split(",")) {
                if (!branch.isBlank()) {
                    branches.add(branch.trim());
                }
            }
        }
        return List.copyOf(branches);
    }

    /**
     * Platform variables first, project variables second.
     *
     * <p>The order looks like the project wins, but {@link ProjectValidator} refuses a project
     * variable whose name is already set by the platform, so the collision never reaches here.
     */
    private Map<String, String> resolveVariables(ProjectEntity project) {
        Map<String, String> variables = new LinkedHashMap<>(settings.sandbox().environment());
        for (ProjectVariableEntity variable : variableRepository.findByProjectIdOrderByNameAsc(project.getId())) {
            variables.put(variable.getName(), variable.getValue());
        }
        return variables;
    }

    private Map<ModelRole, String> resolveModels(ProjectEntity project) {
        Map<ModelRole, String> models = new EnumMap<>(ModelRole.class);
        List<ProjectModelEntity> pinned = modelRepository.findByProjectId(project.getId());
        for (ProjectModelEntity entity : pinned) {
            // A model removed from the allowlist since it was pinned is dropped rather than used:
            // the platform mapping then applies, which is the safe direction.
            if (settings.ai().isModelAllowed(entity.getModelName())) {
                models.put(entity.getRole(), entity.getModelName());
            } else {
                log.warn(
                        "Project {} pins model {} for role {}, which is no longer allowed; falling back to the platform mapping",
                        project.getName(),
                        entity.getModelName(),
                        entity.getRole());
            }
        }
        return models;
    }

    /** Models pinned by a project, unfiltered, for the project screen. */
    @Transactional(readOnly = true)
    public Map<ModelRole, String> pinnedModels(java.util.UUID projectId) {
        Map<ModelRole, String> models = new EnumMap<>(ModelRole.class);
        for (ProjectModelEntity entity : modelRepository.findByProjectId(projectId)) {
            models.put(entity.getRole(), entity.getModelName());
        }
        return models;
    }

    /** Variables of a project, for the project screen. */
    @Transactional(readOnly = true)
    public Map<String, String> variables(java.util.UUID projectId) {
        Map<String, String> variables = new LinkedHashMap<>();
        for (ProjectVariableEntity variable : variableRepository.findByProjectIdOrderByNameAsc(projectId)) {
            variables.put(variable.getName(), variable.getValue());
        }
        return variables;
    }

    /** Commands of a project, for the project screen. */
    @Transactional(readOnly = true)
    public List<List<String>> commands(ProjectEntity project) {
        List<List<String>> commands = new ArrayList<>();
        commands.add(argvCodec.read(project.getBuildCommand()));
        commands.add(argvCodec.read(project.getTestCommand()));
        commands.add(argvCodec.read(project.getLintCommand()));
        return commands;
    }

    /** Jira key a ticket must carry to belong to this project, upper-cased. */
    public static String normalizedJiraKey(ProjectEntity project) {
        String key = project.getJiraProjectKey();
        return key == null || key.isBlank() ? null : key.trim().toUpperCase(Locale.ROOT);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
