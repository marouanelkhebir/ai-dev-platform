package com.mel.aidev.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * Body of {@code POST /api/projects/{projectId}/workflows}.
 *
 * <p>No repository here: it comes from the project, which is the whole point.
 */
public record CreateProjectWorkflowRequest(
        @NotBlank @Pattern(regexp = "[A-Z][A-Z0-9_]+-\\d+", message = "must be a Jira issue key such as BANK-1245")
                String jiraTicket) {}
