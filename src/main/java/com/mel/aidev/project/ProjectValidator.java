package com.mel.aidev.project;

import com.mel.aidev.gitlab.GitLabClient;
import com.mel.aidev.gitlab.GitLabException;
import com.mel.aidev.jira.JiraClient;
import com.mel.aidev.jira.JiraException;
import com.mel.aidev.llm.ModelRole;
import com.mel.aidev.sandbox.CommandGuard;
import com.mel.aidev.sandbox.SandboxException;
import com.mel.aidev.security.ImagePolicy;
import com.mel.aidev.settings.PlatformSettings;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Everything a project must satisfy before it is stored.
 *
 * <p>These checks run at save time on purpose. A repository the token cannot read, a command the
 * sandbox would refuse or a model outside the allowlist are all problems a human can fix in ten
 * seconds while looking at the form, and problems nobody can diagnose forty minutes into a workflow.
 */
@Component
public class ProjectValidator {

    private static final Logger log = LoggerFactory.getLogger(ProjectValidator.class);

    private static final Pattern JIRA_KEY = Pattern.compile("^[A-Z][A-Z0-9]{1,9}$");
    private static final Pattern VARIABLE_NAME = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]{0,127}$");

    /** Variable names that must never carry a value into a container an agent can read. */
    private static final Pattern SECRET_LOOKING_NAME =
            Pattern.compile("(?i).*(secret|token|password|passwd|credential|api[_-]?key|private[_-]?key).*");

    /** Values shaped like a credential, whatever they are called. */
    private static final Pattern SECRET_LOOKING_VALUE =
            Pattern.compile("^(glpat-|ghp_|github_pat_|gho_|sk-|xox[baprs]-|AKIA[0-9A-Z]{16}).*|^[A-Za-z0-9+/]{40,}={0,2}$");

    private final GitLabClient gitLabClient;
    private final JiraClient jiraClient;
    private final CommandGuard commandGuard;
    private final ImagePolicy imagePolicy;
    private final PlatformSettings settings;

    public ProjectValidator(
            GitLabClient gitLabClient,
            JiraClient jiraClient,
            CommandGuard commandGuard,
            ImagePolicy imagePolicy,
            PlatformSettings settings) {
        this.gitLabClient = gitLabClient;
        this.jiraClient = jiraClient;
        this.commandGuard = commandGuard;
        this.imagePolicy = imagePolicy;
        this.settings = settings;
    }

    public void validateName(String name) {
        if (name == null || name.isBlank()) {
            throw new ProjectValidationException("The project name is required");
        }
        if (name.trim().length() > 128) {
            throw new ProjectValidationException("The project name must not exceed 128 characters");
        }
    }

    /**
     * Checks that the repository answers with the configured credentials.
     *
     * @return the default branch reported by GitLab, or null when it reports none
     */
    public String validateGitLabProject(String gitlabProject) {
        return validateRepository(ScmProvider.GITLAB, gitlabProject);
    }

    /** Checks access to a repository using the provider selected by the project. */
    public String validateRepository(ScmProvider provider, String repository) {
        ScmProvider effectiveProvider = provider == null ? ScmProvider.GITLAB : provider;
        if (repository == null || repository.isBlank()) {
            throw new ProjectValidationException("The " + effectiveProvider + " repository is required");
        }
        String projectId = effectiveProvider.qualify(repository);
        try {
            return gitLabClient.getProject(projectId).defaultBranch();
        } catch (GitLabException e) {
            throw new ProjectValidationException(
                    "The " + effectiveProvider + " repository '" + repository + "' is not reachable with the current configuration",
                    List.of(String.valueOf(e.getMessage())));
        }
    }

    public void validateJiraProjectKey(String jiraProjectKey) {
        if (jiraProjectKey == null || jiraProjectKey.isBlank()) {
            return; // a project without Jira only accepts free-form requests, which is legitimate
        }
        String key = jiraProjectKey.trim().toUpperCase(Locale.ROOT);
        if (!JIRA_KEY.matcher(key).matches()) {
            throw new ProjectValidationException(
                    "'" + jiraProjectKey + "' is not a Jira project key (2 to 10 characters, starting with a letter)");
        }
        if (!settings.jira().isConfigured()) {
            return;
        }
        try {
            if (!jiraClient.projectExists(key)) {
                throw new ProjectValidationException("No Jira project '" + key + "' exists on the configured instance");
            }
        } catch (JiraException e) {
            // Jira being momentarily unavailable must not block the creation of a project: the key
            // format is already validated, and the workflow will fail loudly if the key is wrong.
            log.warn("Unable to verify the Jira project {}, accepting it: {}", key, e.toString());
        }
    }

    public void validateImage(String dockerImage) {
        if (dockerImage == null || dockerImage.isBlank()) {
            return; // the global per-profile configuration applies
        }
        try {
            imagePolicy.assertAllowed(dockerImage.trim());
        } catch (ImagePolicy.ImagePolicyViolationException e) {
            throw new ProjectValidationException(e.getMessage());
        }
    }

    public void validateBranchPrefix(String branchPrefix) {
        if (branchPrefix == null || branchPrefix.isBlank()) {
            return;
        }
        String prefix = branchPrefix.trim();
        if (!prefix.endsWith("/")) {
            throw new ProjectValidationException("The branch prefix must end with '/', for example 'ai/'");
        }
        if (!prefix.matches("[A-Za-z0-9._/-]+")) {
            throw new ProjectValidationException("The branch prefix contains characters a branch name cannot carry");
        }
        String withoutSlash = prefix.substring(0, prefix.length() - 1).toLowerCase(Locale.ROOT);
        boolean collidesWithProtected = settings.gitlab().protectedBranches().stream()
                .anyMatch(protectedBranch -> protectedBranch.toLowerCase(Locale.ROOT).equals(withoutSlash));
        if (collidesWithProtected) {
            throw new ProjectValidationException(
                    "The branch prefix '" + prefix + "' targets a protected branch namespace");
        }
    }

    /**
     * Checks that a command would actually be accepted by the sandbox.
     *
     * <p>Runs the very guard that will run at execution time rather than a copy of its rules: a
     * second implementation would drift, and the one that matters is the one in the sandbox.
     */
    public void validateCommand(String label, List<String> argv) {
        if (argv == null || argv.isEmpty()) {
            return;
        }
        try {
            commandGuard.validate(argv);
        } catch (SandboxException e) {
            throw new ProjectValidationException("The " + label + " command is refused by the sandbox", List.of(e.getMessage()));
        }
    }

    public void validateModels(Map<ModelRole, String> models) {
        if (models == null || models.isEmpty()) {
            return;
        }
        List<String> rejected = models.entrySet().stream()
                .filter(entry -> !settings.ai().isModelAllowed(entry.getValue()))
                .map(entry -> entry.getKey() + " → " + entry.getValue())
                .toList();
        if (!rejected.isEmpty()) {
            throw new ProjectValidationException(
                    "These models are not part of the platform allowlist (ai.models.allowed)", rejected);
        }
    }

    /**
     * Checks a variable that will be injected in clear text into the sandbox environment.
     *
     * <p>The container is readable by the agents, so a credential placed here is a credential handed
     * to a model. Secrets belong in the settings screen, encrypted, where the sandbox never sees them.
     */
    public void validateVariable(String name, String value) {
        if (name == null || !VARIABLE_NAME.matcher(name).matches()) {
            throw new ProjectValidationException(
                    "'" + name + "' is not a valid environment variable name (letters, digits and underscore)");
        }
        if (SECRET_LOOKING_NAME.matcher(name).matches()) {
            throw new ProjectValidationException(
                    "The variable '" + name + "' looks like a secret; store it in the settings screen instead."
                            + " Project variables reach the container in clear text and are readable by the agents.");
        }
        if (value == null) {
            throw new ProjectValidationException("The variable '" + name + "' has no value");
        }
        if (value.length() > 2048) {
            throw new ProjectValidationException("The value of '" + name + "' exceeds 2048 characters");
        }
        if (SECRET_LOOKING_VALUE.matcher(value.trim()).matches()) {
            throw new ProjectValidationException(
                    "The value of '" + name + "' looks like a credential and cannot be stored as a project variable");
        }
        if (settings.sandbox().environment().containsKey(name)) {
            throw new ProjectValidationException(
                    "'" + name + "' is set by the platform sandbox configuration and cannot be overridden by a project");
        }
    }
}
