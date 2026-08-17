package com.mel.aidev.project;

import com.mel.aidev.llm.ModelRole;
import java.util.List;
import java.util.Map;

/**
 * What a human describes when creating or editing a project.
 *
 * <p>Separate from the API request records on purpose: the service enforces the rules of the domain
 * and must not change shape every time the screen gains a field.
 *
 * @param name unique display name
 * @param description free text, optional
 * @param gitlabProject GitLab path or numeric id
 * @param jiraProjectKey Jira key, null for a project driven only by free-form requests
 * @param dockerImage pinned image, null to use the global per-profile image
 * @param defaultBranch target branch, null to use the default branch reported by GitLab
 * @param branchPrefix prefix of the AI branches, null to use the global prefix
 * @param protectedBranches branches this project protects on top of the global list
 * @param buildCommand argv of the build command, empty for the profile default
 * @param testCommand argv of the test command, empty for the profile default
 * @param lintCommand argv of the lint command, empty for the profile default
 * @param retentionDays override of the detail retention, 0 to keep the details forever
 * @param variables non-sensitive variables injected into the sandbox
 * @param models model pinned per role
 * @param active whether the project may start workflows
 */
public record ProjectDefinition(
        String name,
        String description,
        String gitlabProject,
        String jiraProjectKey,
        String dockerImage,
        String defaultBranch,
        String branchPrefix,
        List<String> protectedBranches,
        List<String> buildCommand,
        List<String> testCommand,
        List<String> lintCommand,
        Integer retentionDays,
        Map<String, String> variables,
        Map<ModelRole, String> models,
        Boolean active) {

    public ProjectDefinition {
        protectedBranches = protectedBranches == null ? List.of() : List.copyOf(protectedBranches);
        buildCommand = buildCommand == null ? List.of() : List.copyOf(buildCommand);
        testCommand = testCommand == null ? List.of() : List.copyOf(testCommand);
        lintCommand = lintCommand == null ? List.of() : List.copyOf(lintCommand);
        variables = variables == null ? Map.of() : Map.copyOf(variables);
        models = models == null ? Map.of() : Map.copyOf(models);
    }
}
