package com.company.aidev.sandbox;

import java.time.Instant;
import java.util.UUID;

/**
 * Handle on an isolated execution environment for one ticket.
 *
 * @param id platform-side identifier
 * @param containerId Docker container identifier
 * @param workspacePath absolute path of the workspace inside the container, e.g. {@code /workspaces/BANK-1245}
 * @param repositoryPath absolute path of the cloned repository inside the workspace
 * @param workflowId owning workflow
 * @param jiraTicket ticket this sandbox belongs to
 * @param createdAt creation timestamp, used to enforce the maximum lifetime
 */
public record Sandbox(
        UUID id,
        String containerId,
        String workspacePath,
        String repositoryPath,
        UUID workflowId,
        String jiraTicket,
        Instant createdAt) {

    public Sandbox withRepositoryPath(String newRepositoryPath) {
        return new Sandbox(id, containerId, workspacePath, newRepositoryPath, workflowId, jiraTicket, createdAt);
    }
}
