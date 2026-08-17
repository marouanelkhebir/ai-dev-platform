package com.mel.aidev.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Request body of {@code POST /api/workflows/message}. */
public record CreateMessageWorkflowRequest(
        @NotBlank @Size(max = 50_000) String message,
        @NotBlank String gitlabProjectId) {}
