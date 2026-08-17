package com.mel.aidev.gitlab.model;

/** Typed view of a GitLab project. */
public record GitLabProject(
        long id, String name, String pathWithNamespace, String defaultBranch, String webUrl, String httpUrlToRepo) {}
