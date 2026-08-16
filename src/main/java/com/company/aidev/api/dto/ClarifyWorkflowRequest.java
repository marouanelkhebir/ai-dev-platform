package com.company.aidev.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Human-provided information that lets a blocked workflow resume its analysis. */
public record ClarifyWorkflowRequest(@NotBlank @Size(max = 50_000) String clarification) {}
