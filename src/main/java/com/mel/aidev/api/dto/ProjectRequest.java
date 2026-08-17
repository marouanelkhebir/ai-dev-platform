package com.mel.aidev.api.dto;

import com.mel.aidev.llm.ModelRole;
import com.mel.aidev.project.ProjectDefinition;
import com.mel.aidev.project.ScmProvider;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;

/**
 * Body of {@code POST /api/projects} and {@code PUT /api/projects/{id}}.
 *
 * <p>Creation and update take the same shape because a project is described in full every time: a
 * partial update would make "no value" and "remove the value" indistinguishable for every optional
 * field, and the screen sends the whole form anyway.
 *
 * @param name unique display name
 * @param scmProvider source-control provider; omitted values keep GitLab for backward compatibility
 * @param gitlabProject repository path: GitLab group/project, Bitbucket workspace/repository, or
 *     GitHub owner/repository
 * @param jiraProjectKey Jira key, e.g. {@code BANK}; null for a project driven by free-form requests
 * @param dockerImage pinned image; null to use the global per-profile image
 * @param defaultBranch target branch; null to follow the default branch of the repository
 * @param branchPrefix prefix of the AI branches; null to use the global prefix
 * @param protectedBranches branches this project protects on top of the global list
 * @param buildCommand argv of the build command, e.g. {@code ["mvn","-B","-ntp","test-compile"]}
 * @param testCommand argv of the test command
 * @param lintCommand argv of the lint command
 * @param retentionDays retention of the detailed payloads; 0 keeps them forever
 * @param variables non-sensitive variables injected into the sandbox
 * @param models model pinned per logical role
 * @param active whether the project may start workflows
 */
public record ProjectRequest(
        @NotBlank @Size(max = 128) String name,
        @Size(max = 2000) String description,
        ScmProvider scmProvider,
        @NotBlank @Size(max = 512) String gitlabProject,
        @Size(max = 32) String jiraProjectKey,
        @Size(max = 512) String dockerImage,
        @Size(max = 255) String defaultBranch,
        @Size(max = 64) String branchPrefix,
        List<String> protectedBranches,
        List<String> buildCommand,
        List<String> testCommand,
        List<String> lintCommand,
        Integer retentionDays,
        Map<String, String> variables,
        Map<ModelRole, String> models,
        Boolean active) {

    public ProjectDefinition toDefinition() {
        return new ProjectDefinition(
                name,
                description,
                scmProvider,
                gitlabProject,
                jiraProjectKey,
                dockerImage,
                defaultBranch,
                branchPrefix,
                protectedBranches,
                buildCommand,
                testCommand,
                lintCommand,
                retentionDays,
                variables,
                models,
                active);
    }
}
