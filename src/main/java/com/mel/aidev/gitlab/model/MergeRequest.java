package com.mel.aidev.gitlab.model;

/** Typed view of a GitLab merge request. */
public record MergeRequest(
        long id,
        long iid,
        String title,
        String description,
        String sourceBranch,
        String targetBranch,
        String state,
        String webUrl,
        String sha,
        Boolean hasConflicts,
        String detailedMergeStatus) {

    public boolean isOpen() {
        return "opened".equalsIgnoreCase(state);
    }
}
