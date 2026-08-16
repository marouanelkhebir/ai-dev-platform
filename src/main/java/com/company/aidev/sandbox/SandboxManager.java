package com.company.aidev.sandbox;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * Lifecycle and driving of isolated workspaces.
 *
 * <p>The platform process never runs a build and never writes source files on its own filesystem:
 * everything goes through this interface, which is the only place where untrusted, model-driven
 * execution happens.
 */
public interface SandboxManager {

    /** Creates a throwaway environment for one ticket. */
    Sandbox createSandbox(UUID workflowId, String jiraTicket);

    /** Runs a command with the default timeout, from the repository root. */
    CommandResult execute(Sandbox sandbox, List<String> command);

    /** Runs a command from a given working directory with an explicit timeout. */
    CommandResult execute(Sandbox sandbox, List<String> command, String workingDirectory, Duration timeout);

    /**
     * Runs a command with extra environment variables.
     *
     * <p>Reserved for platform-issued git operations: it is how the GitLab token reaches git as an
     * {@code http.extraheader} instead of being embedded in the remote URL, where it would end up in
     * {@code .git/config} and in every {@code git remote -v} output an agent can read.
     */
    CommandResult execute(
            Sandbox sandbox,
            List<String> command,
            String workingDirectory,
            Duration timeout,
            java.util.Map<String, String> environment);

    /** Reads a UTF-8 file from the sandbox. */
    String readFile(Sandbox sandbox, String relativePath);

    /** Writes a UTF-8 file into the sandbox, creating parent directories as needed. */
    void writeFile(Sandbox sandbox, String relativePath, String content);

    /** Returns true when the path exists inside the sandbox. */
    boolean exists(Sandbox sandbox, String relativePath);

    /** Destroys the environment. Implementations must never throw here. */
    void destroySandbox(Sandbox sandbox);

    /**
     * Removes environments left behind by a crash or by a workflow that exceeded its lifetime.
     *
     * @return the number of environments removed
     */
    default int cleanupStaleSandboxes() {
        return 0;
    }
}
