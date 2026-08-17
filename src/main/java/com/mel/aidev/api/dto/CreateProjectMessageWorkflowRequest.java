package com.mel.aidev.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Body of {@code POST /api/projects/{projectId}/workflows/message}. */
public record CreateProjectMessageWorkflowRequest(@NotBlank @Size(max = 50_000) String message) {}
