package com.company.aidev.tool;

import com.company.aidev.git.GitOperations;
import dev.langchain4j.agent.tool.Tool;

/**
 * Read-only git tools.
 *
 * <p>There is no commit, no push and no branch tool here on purpose: what becomes a commit and where
 * it lands is a platform decision, not an agent decision.
 */
public class GitTools {

    private static final int MAX_DIFF_CHARS = 60_000;

    private final GitOperations gitOperations;
    private final ToolExecutionRecorder recorder;
    private final ToolContext context;

    public GitTools(GitOperations gitOperations, ToolExecutionRecorder recorder, ToolContext context) {
        this.gitOperations = gitOperations;
        this.recorder = recorder;
        this.context = context;
    }

    @Tool("Show the current git status of the workspace (which files were created, modified or deleted).")
    public String gitStatus() {
        return recorder.record(
                context.workflowId(),
                context.agentExecutionId(),
                "gitStatus",
                "",
                () -> {
                    String status = gitOperations.status(context.sandbox());
                    return status.isBlank() ? "Working tree clean, no change yet." : status;
                });
    }

    @Tool("Show the unified diff of everything changed in the workspace since the last commit.")
    public String gitDiff() {
        return recorder.record(
                context.workflowId(),
                context.agentExecutionId(),
                "gitDiff",
                "",
                () -> {
                    String diff = gitOperations.diff(context.sandbox(), MAX_DIFF_CHARS);
                    return diff.isBlank() ? "No change yet." : diff;
                });
    }
}
