package com.mel.aidev.tool;

import com.mel.aidev.project.ProjectRuntimeContext;
import com.mel.aidev.sandbox.CommandResult;
import com.mel.aidev.sandbox.SandboxManager;
import dev.langchain4j.agent.tool.Tool;
import java.time.Duration;
import java.util.List;

/** Safe Python dependency and test commands for Python 3.11+ repositories. */
public class PythonTools {

    private static final Duration BUILD_TIMEOUT = Duration.ofMinutes(20);
    private static final int MAX_OUTPUT_CHARS = 30_000;

    private final SandboxManager sandboxManager;
    private final ToolExecutionRecorder recorder;
    private final ToolContext context;

    public PythonTools(SandboxManager sandboxManager, ToolExecutionRecorder recorder, ToolContext context) {
        this.sandboxManager = sandboxManager;
        this.recorder = recorder;
        this.context = context;
    }

    @Tool("Install declared Python dependencies. Uses requirements.txt when present, otherwise installs the project in editable mode. Returns the command output.")
    public String installDependencies() {
        List<String> command = sandboxManager.exists(context.sandbox(), "requirements.txt")
                ? List.of("python", "-m", "pip", "install", "-r", "requirements.txt")
                : List.of("python", "-m", "pip", "install", "-e", ".");
        return execute("pythonInstall", command);
    }

    @Tool("Install dependencies locked in requirements.txt. Returns the command output.")
    public String installRequirements() {
        return execute("pythonRequirements", List.of("python", "-m", "pip", "install", "-r", "requirements.txt"));
    }

    @Tool("Run the Python test suite with pytest. Returns the command output.")
    public String runTests() {
        List<String> configured = ProjectRuntimeContext.currentTestCommand();
        return execute("pythonPytest", configured.isEmpty() ? List.of("python", "-m", "pytest") : configured);
    }

    private String execute(String toolName, List<String> command) {
        return recorder.record(context.workflowId(), context.agentExecutionId(), toolName, String.join(" ", command), () -> {
            CommandResult result = sandboxManager.execute(
                    context.sandbox(), command, context.sandbox().repositoryPath(), BUILD_TIMEOUT);
            return result.toToolOutput(MAX_OUTPUT_CHARS);
        });
    }
}
