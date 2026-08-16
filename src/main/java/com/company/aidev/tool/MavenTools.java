package com.company.aidev.tool;

import com.company.aidev.sandbox.CommandResult;
import com.company.aidev.sandbox.SandboxManager;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Build and test execution tools.
 *
 * <p>Maven goals are validated against an allowlist. Without it, a model asking for
 * {@code mvn deploy} would publish an artifact built from unreviewed code, and
 * {@code mvn -Dexec.executable=...} would be arbitrary code execution with the sandbox's network.
 */
public class MavenTools {

    private static final int MAX_OUTPUT_CHARS = 30_000;
    private static final Duration BUILD_TIMEOUT = Duration.ofMinutes(20);

    /** Goals and lifecycle phases an agent may run. */
    private static final Set<String> ALLOWED_GOALS =
            Set.of("clean", "validate", "compile", "test", "test-compile", "verify", "package", "dependency:tree");

    /** Only these system properties may be set, and only to simple values. */
    private static final Set<String> ALLOWED_PROPERTIES = Set.of("test", "it.test", "surefire.failIfNoSpecifiedTests");

    private final SandboxManager sandboxManager;
    private final ToolExecutionRecorder recorder;
    private final ToolContext context;

    public MavenTools(SandboxManager sandboxManager, ToolExecutionRecorder recorder, ToolContext context) {
        this.sandboxManager = sandboxManager;
        this.recorder = recorder;
        this.context = context;
    }

    @Tool("Run the unit tests of the project (mvn test). Returns the build output.")
    public String runTests() {
        return execute("runTests", List.of("test"));
    }

    @Tool("Run a single test class or method, e.g. FeeServiceTest or FeeServiceTest#shouldSuspendFee.")
    public String runSingleTest(@P("Test class, optionally suffixed with #method") String testSelector) {
        if (testSelector == null || testSelector.isBlank()) {
            return "ERROR: empty test selector";
        }
        if (!testSelector.matches("[A-Za-z0-9_.$*#,+-]+")) {
            return "ERROR: invalid test selector: " + testSelector;
        }
        return execute(
                "runSingleTest",
                List.of("test", "-Dtest=" + testSelector, "-Dsurefire.failIfNoSpecifiedTests=false"));
    }

    @Tool("Run the full verification, including integration tests (mvn verify).")
    public String runVerify() {
        return execute("runVerify", List.of("verify"));
    }

    @Tool("Compile the project without running the tests (mvn test-compile).")
    public String compile() {
        return execute("compile", List.of("test-compile"));
    }

    @Tool("Run an arbitrary Maven command from an allowlist of goals: clean, compile, test, verify, package, dependency:tree.")
    public String runMavenCommand(@P("Space-separated Maven goals and options") String goals) {
        List<String> arguments = goals == null ? List.of() : List.of(goals.trim().split("\\s+"));
        return execute("runMavenCommand", arguments);
    }

    private String execute(String toolName, List<String> mavenArguments) {
        String rendered = String.join(" ", mavenArguments);
        return recorder.record(context.workflowId(), context.agentExecutionId(), toolName, rendered, () -> {
            List<String> command = buildCommand(mavenArguments);
            CommandResult result = sandboxManager.execute(
                    context.sandbox(), command, context.sandbox().repositoryPath(), BUILD_TIMEOUT);
            return result.toToolOutput(MAX_OUTPUT_CHARS);
        });
    }

    /**
     * Builds the argv, preferring the Maven wrapper when the repository ships one so that the build
     * uses the same Maven version as the CI.
     */
    List<String> buildCommand(List<String> mavenArguments) {
        List<String> validated = new ArrayList<>();
        for (String argument : mavenArguments) {
            if (argument.isBlank()) {
                continue;
            }
            if (argument.startsWith("-D")) {
                String property = argument.substring(2).split("=", 2)[0];
                if (!ALLOWED_PROPERTIES.contains(property)) {
                    throw new IllegalArgumentException("System property not allowed: -D" + property);
                }
                validated.add(argument);
                continue;
            }
            if (argument.startsWith("-")) {
                throw new IllegalArgumentException("Maven option not allowed: " + argument);
            }
            if (!ALLOWED_GOALS.contains(argument)) {
                throw new IllegalArgumentException("Maven goal not allowed: " + argument + " (allowed: " + ALLOWED_GOALS + ")");
            }
            validated.add(argument);
        }
        if (validated.isEmpty()) {
            validated.add("test");
        }

        boolean hasWrapper = sandboxManager.exists(context.sandbox(), "mvnw");
        List<String> command = new ArrayList<>();
        command.add(hasWrapper ? "./mvnw" : "mvn");
        command.add("-B");
        command.add("-ntp");
        command.addAll(validated);
        return command;
    }
}
