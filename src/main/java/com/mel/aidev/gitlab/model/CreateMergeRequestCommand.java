package com.mel.aidev.gitlab.model;

import java.util.List;

/** Parameters of a merge request creation. */
public record CreateMergeRequestCommand(
        String projectId,
        String sourceBranch,
        String targetBranch,
        String title,
        String description,
        List<String> labels,
        boolean removeSourceBranch,
        boolean squash) {

    public CreateMergeRequestCommand {
        labels = labels == null ? List.of() : List.copyOf(labels);
    }
}
