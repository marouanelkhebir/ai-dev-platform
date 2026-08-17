package com.mel.aidev.project;

import com.mel.aidev.gitlab.ScmProjectId;

/** Source-control services supported by a project. */
public enum ScmProvider {
    GITLAB,
    BITBUCKET,
    GITHUB;

    /**
     * Qualifies a repository path with the marker the SCM clients route on.
     *
     * <p>GitLab identifiers stay untouched: they are what the column held before providers existed,
     * and rewriting them would break every workflow already recorded against them.
     *
     * <p>Idempotent on purpose. Callers hand over both what a human typed and what was persisted —
     * {@code ProjectService.restore} re-validates the stored identifier — and qualifying an already
     * qualified path would produce {@code github:github:owner/repo}, which no provider can resolve.
     */
    public String qualify(String repository) {
        String trimmed = ScmProjectId.repository(repository == null ? "" : repository.trim());
        return switch (this) {
            case GITLAB -> trimmed;
            case BITBUCKET -> ScmProjectId.bitbucket(trimmed);
            case GITHUB -> ScmProjectId.github(trimmed);
        };
    }
}
