package com.company.aidev.tool;

import com.company.aidev.gitlab.GitLabClient;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import java.util.List;
import java.util.UUID;

/**
 * Read-only exploration of a repository through the GitLab API.
 *
 * <p>Used by the architect agent, which must inspect the code but must not modify anything and does
 * not need a container. Not spinning up a sandbox for planning keeps the common path fast and keeps
 * the architect structurally incapable of writing.
 */
public class RepositoryReadTools {

    private static final int MAX_FILE_CHARS = 60_000;
    private static final int MAX_LIST_ENTRIES = 300;
    private static final int MAX_SEARCH_RESULTS = 25;

    private final GitLabClient gitLabClient;
    private final ToolExecutionRecorder recorder;
    private final UUID workflowId;
    private final UUID agentExecutionId;
    private final String projectId;
    private final String ref;

    public RepositoryReadTools(
            GitLabClient gitLabClient,
            ToolExecutionRecorder recorder,
            UUID workflowId,
            UUID agentExecutionId,
            String projectId,
            String ref) {
        this.gitLabClient = gitLabClient;
        this.recorder = recorder;
        this.workflowId = workflowId;
        this.agentExecutionId = agentExecutionId;
        this.projectId = projectId;
        this.ref = ref;
    }

    @Tool("Read a file of the repository at the base branch. The path is relative to the repository root.")
    public String readRepositoryFile(@P("Path relative to the repository root") String path) {
        return recorder.record(workflowId, agentExecutionId, "readRepositoryFile", path, () -> gitLabClient
                .readFile(projectId, ref, path)
                .map(content -> content.length() > MAX_FILE_CHARS
                        ? content.substring(0, MAX_FILE_CHARS) + "\n...[file truncated]"
                        : content)
                .orElse("File not found: " + path));
    }

    @Tool("List the files of the repository, recursively.")
    public String listRepositoryFiles() {
        return recorder.record(workflowId, agentExecutionId, "listRepositoryFiles", ref, () -> {
            List<String> files = gitLabClient.listRepositoryFiles(projectId, ref, MAX_LIST_ENTRIES);
            return files.isEmpty() ? "No file found" : String.join("\n", files);
        });
    }

    @Tool("Search a term in the repository source code. Returns the matching files with an excerpt.")
    public String searchRepository(@P("Term or symbol to search, e.g. FeeSuspensionService") String query) {
        return recorder.record(workflowId, agentExecutionId, "searchRepository", query, () -> {
            List<String> results = gitLabClient.searchCode(projectId, ref, query, MAX_SEARCH_RESULTS);
            return results.isEmpty() ? "No match for " + query : String.join("\n---\n", results);
        });
    }
}
