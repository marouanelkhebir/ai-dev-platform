package com.mel.aidev.project;

import com.mel.aidev.llm.ModelRole;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The configuration a workflow actually runs with, project values resolved over global ones.
 *
 * <p>An instance is serialised onto the workflow at launch and read back on every later step,
 * including retries. That is what makes editing a project safe: it changes the next workflow, never
 * the history of one that already ran.
 *
 * @param projectId owning project
 * @param projectName owning project name, carried for the audit view
 * @param gitlabProject GitLab path or numeric id
 * @param jiraProjectKey Jira key the tickets of this project must belong to, may be null
 * @param sandboxImage image the sandbox is started with
 * @param baseBranch branch the merge requests target
 * @param branchPrefix prefix every branch written by the agents must carry
 * @param protectedBranches branches forbidden in writing, global list included
 * @param buildCommand argv of the build command, empty to use the profile default
 * @param testCommand argv of the test command, empty to use the profile default
 * @param lintCommand argv of the lint command, empty to use the profile default
 * @param variables non-sensitive variables injected into the sandbox
 * @param models model pinned per role, empty to use the platform mapping
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ProjectConfiguration(
        UUID projectId,
        String projectName,
        String gitlabProject,
        String jiraProjectKey,
        String sandboxImage,
        String baseBranch,
        String branchPrefix,
        List<String> protectedBranches,
        List<String> buildCommand,
        List<String> testCommand,
        List<String> lintCommand,
        Map<String, String> variables,
        Map<ModelRole, String> models) {

    public ProjectConfiguration {
        protectedBranches = protectedBranches == null ? List.of() : List.copyOf(protectedBranches);
        buildCommand = buildCommand == null ? List.of() : List.copyOf(buildCommand);
        testCommand = testCommand == null ? List.of() : List.copyOf(testCommand);
        lintCommand = lintCommand == null ? List.of() : List.copyOf(lintCommand);
        variables = variables == null ? Map.of() : Map.copyOf(variables);
        models = models == null ? Map.of() : Map.copyOf(models);
    }

    /** Model pinned for a role, or null when the platform mapping applies. */
    public String modelFor(ModelRole role) {
        return role == null ? null : models.get(role);
    }

    public boolean hasTestCommand() {
        return !testCommand.isEmpty();
    }

    public boolean hasBuildCommand() {
        return !buildCommand.isEmpty();
    }

    public boolean hasLintCommand() {
        return !lintCommand.isEmpty();
    }

    /** Branch this project uses for a ticket, e.g. {@code ai/BANK-1245}. */
    public String branchFor(String jiraTicket) {
        String sanitized = jiraTicket.trim().replaceAll("[^A-Za-z0-9._/-]", "-");
        return branchPrefix + sanitized;
    }
}
