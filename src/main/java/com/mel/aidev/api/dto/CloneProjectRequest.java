package com.mel.aidev.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Body of {@code POST /api/projects/{id}/clone}.
 *
 * <p>Only the name is required; the other fields override the source when supplied. Workflows are
 * never copied, and the clone goes through the full validation of a creation.
 */
public record CloneProjectRequest(
        @NotBlank @Size(max = 128) String name,
        @Size(max = 512) String gitlabProject,
        @Size(max = 32) String jiraProjectKey,
        @Size(max = 512) String dockerImage) {}
