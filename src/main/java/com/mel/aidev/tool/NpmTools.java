package com.mel.aidev.tool;

import com.mel.aidev.project.ProjectRuntimeContext;
import com.mel.aidev.sandbox.CommandResult;
import com.mel.aidev.sandbox.SandboxManager;
import dev.langchain4j.agent.tool.Tool;
import java.time.Duration;
import java.util.List;

/** Safe Node/Angular build commands. No arbitrary npm scripts are exposed to the model. */
public class NpmTools {

    private static final Duration BUILD_TIMEOUT = Duration.ofMinutes(20);
    private static final int MAX_OUTPUT_CHARS = 30_000;

    private final SandboxManager sandboxManager;
    private final ToolExecutionRecorder recorder;
    private final ToolContext context;

    public NpmTools(SandboxManager sandboxManager, ToolExecutionRecorder recorder, ToolContext context) {
        this.sandboxManager = sandboxManager;
        this.recorder = recorder;
        this.context = context;
    }

    @Tool("Install the locked Node dependencies with npm ci. Returns the command output.")
    public String installDependencies() {
        return execute("npmCi", List.of("npm", "ci"));
    }

    @Tool("Run the Angular unit tests once, without watch mode. Returns the command output.")
    public String runTests() {
        return execute("npmTest", orConfigured(ProjectRuntimeContext.currentTestCommand(), List.of("npm", "test", "--", "--watch=false")));
    }

    @Tool("Build the Angular application in its production configuration. Returns the command output.")
    public String build() {
        return execute("npmBuild", orConfigured(ProjectRuntimeContext.currentBuildCommand(), List.of("npm", "run", "build")));
    }

    @Tool("Run the repository lint script. Returns the command output.")
    public String lint() {
        return execute("npmLint", orConfigured(ProjectRuntimeContext.currentLintCommand(), List.of("npm", "run", "lint")));
    }

    /** The command a human configured on the project, or the default of the profile. */
    private static List<String> orConfigured(List<String> configured, List<String> fallback) {
        return configured.isEmpty() ? fallback : configured;
    }

    private String execute(String toolName, List<String> command) {
        return recorder.record(context.workflowId(), context.agentExecutionId(), toolName, String.join(" ", command), () -> {
            CommandResult result = sandboxManager.execute(
                    context.sandbox(), command, context.sandbox().repositoryPath(), BUILD_TIMEOUT);
            return result.toToolOutput(MAX_OUTPUT_CHARS);
        });
    }
}
