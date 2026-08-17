package com.mel.aidev.gitlab.model;

/** Typed view of a GitLab pipeline. */
public record Pipeline(long id, PipelineStatus status, String ref, String sha, String webUrl) {}
