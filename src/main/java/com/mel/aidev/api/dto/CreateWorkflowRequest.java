package com.mel.aidev.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * Request body of {@code POST /api/workflows}.
 *
 * @param jiraTicket Jira issue key, e.g. {@code BANK-1245}
 * @param gitlabProjectId GitLab project path or numeric id, e.g. {@code bank/customer-management}
 */
public record CreateWorkflowRequest(
        @NotBlank @Pattern(regexp = "[A-Z][A-Z0-9_]+-\\d+", message = "must be a Jira issue key such as BANK-1245")
                String jiraTicket,
        @NotBlank String gitlabProjectId) {}
