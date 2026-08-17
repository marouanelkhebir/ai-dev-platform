package com.mel.aidev.api.dto;

import com.mel.aidev.llm.ModelRole;
import com.mel.aidev.project.ProjectConfiguration;
import java.util.List;
import java.util.Map;

/**
 * Full view of a project: what a human configured, and what it resolves to.
 *
 * <p>Both are returned because they differ, and the difference is what a reader needs: the fields
 * left empty on the form are the ones that follow the platform configuration, and the resolved
 * block says what they currently follow it to.
 *
 * @param summary list-level view
 * @param protectedBranches branches added by this project, without the global ones
 * @param resolved everything a workflow launched now would run with
 */
public record ProjectDetailResponse(
        ProjectResponse summary,
        String branchPrefix,
        List<String> protectedBranches,
        List<String> buildCommand,
        List<String> testCommand,
        List<String> lintCommand,
        Integer retentionDays,
        Map<String, String> variables,
        Map<ModelRole, String> models,
        long activeWorkflowCount,
        ProjectConfiguration resolved) {}
