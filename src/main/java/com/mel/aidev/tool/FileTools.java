package com.mel.aidev.tool;

import com.mel.aidev.sandbox.CommandResult;
import com.mel.aidev.sandbox.SandboxManager;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import java.time.Duration;
import java.util.List;

/**
 * Read and write tools scoped to one sandbox.
 *
 * <p>Every path goes through the workspace guard, so a model-provided {@code ../../etc/passwd} fails
 * loudly instead of escaping the repository.
 */
public class FileTools {

    private static final int MAX_READ_CHARS = 120_000;
    private static final int MAX_LIST_ENTRIES = 400;
    private static final int MAX_SEARCH_CHARS = 20_000;
    private static final Duration SEARCH_TIMEOUT = Duration.ofMinutes(2);

    private final SandboxManager sandboxManager;
    private final ToolExecutionRecorder recorder;
    private final ToolContext context;

    public FileTools(SandboxManager sandboxManager, ToolExecutionRecorder recorder, ToolContext context) {
        this.sandboxManager = sandboxManager;
        this.recorder = recorder;
        this.context = context;
    }

    @Tool("Read a UTF-8 text file from the repository. The path is relative to the repository root.")
    public String readFile(@P("Path relative to the repository root, e.g. src/main/java/com/acme/Fee.java") String path) {
        return recorder.record(context.workflowId(), context.agentExecutionId(), "readFile", path, () -> {
            String content = sandboxManager.readFile(context.sandbox(), path);
            if (content.length() > MAX_READ_CHARS) {
                return content.substring(0, MAX_READ_CHARS) + "\n...[file truncated at " + MAX_READ_CHARS + " chars]";
            }
            return content;
        });
    }

    @Tool("Create or overwrite a text file in the repository. Always write the complete file content.")
    public String writeFile(
            @P("Path relative to the repository root") String path, @P("Full new content of the file") String content) {
        return recorder.record(
                context.workflowId(),
                context.agentExecutionId(),
                "writeFile",
                path + " (" + (content == null ? 0 : content.length()) + " chars)",
                () -> {
                    sandboxManager.writeFile(context.sandbox(), path, content);
                    return "Written " + path;
                });
    }

    @Tool("Check whether a file or directory exists in the repository.")
    public String fileExists(@P("Path relative to the repository root") String path) {
        return recorder.record(
                context.workflowId(),
                context.agentExecutionId(),
                "fileExists",
                path,
                () -> Boolean.toString(sandboxManager.exists(context.sandbox(), path)));
    }

    @Tool("List the files of a directory of the repository, recursively, up to 400 entries.")
    public String listFiles(@P("Directory relative to the repository root, use '.' for the root") String directory) {
        return recorder.record(context.workflowId(), context.agentExecutionId(), "listFiles", directory, () -> {
            String target = directory == null || directory.isBlank() ? "." : directory;
            CommandResult result = sandboxManager.execute(
                    context.sandbox(),
                    List.of("git", "ls-files", "--cached", "--others", "--exclude-standard", target),
                    context.sandbox().repositoryPath(),
                    SEARCH_TIMEOUT);
            if (!result.successful()) {
                return "ERROR: " + result.combinedOutput();
            }
            String[] lines = result.stdout().split("\\R");
            if (lines.length <= MAX_LIST_ENTRIES) {
                return result.stdout();
            }
            return String.join("\n", List.of(lines).subList(0, MAX_LIST_ENTRIES))
                    + "\n...[" + (lines.length - MAX_LIST_ENTRIES) + " more entries]";
        });
    }

    @Tool("Search a regular expression in the source code. Returns matching lines with file and line number.")
    public String searchCode(
            @P("Regular expression, POSIX extended syntax") String pattern,
            @P("Glob filter on file names, e.g. *.java or *.yml. Use * for all files.") String filePattern) {
        String arguments = pattern + " in " + filePattern;
        return recorder.record(context.workflowId(), context.agentExecutionId(), "searchCode", arguments, () -> {
            String include = filePattern == null || filePattern.isBlank() ? "*" : filePattern;
            CommandResult result = sandboxManager.execute(
                    context.sandbox(),
                    List.of("grep", "-rnE", "--include=" + include, "--exclude-dir=.git", "--exclude-dir=target", pattern, "."),
                    context.sandbox().repositoryPath(),
                    SEARCH_TIMEOUT);
            // grep exits 1 when nothing matched, which is a valid answer and not an error.
            if (result.exitCode() == 1) {
                return "No match for " + pattern;
            }
            if (!result.successful()) {
                return "ERROR: " + result.combinedOutput();
            }
            String output = result.stdout();
            return output.length() > MAX_SEARCH_CHARS
                    ? output.substring(0, MAX_SEARCH_CHARS) + "\n...[search results truncated]"
                    : output;
        });
    }
}
