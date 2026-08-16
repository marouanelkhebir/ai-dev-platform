package com.company.aidev.agent;

import com.company.aidev.config.SandboxProperties;
import com.company.aidev.domain.RepositoryRules;
import com.company.aidev.domain.TestReport;
import com.company.aidev.domain.TicketAnalysis;
import com.company.aidev.git.GitOperations;
import com.company.aidev.llm.PromptLoader;
import com.company.aidev.persistence.entity.AgentExecutionEntity;
import com.company.aidev.sandbox.CommandResult;
import com.company.aidev.sandbox.Sandbox;
import com.company.aidev.sandbox.SandboxManager;
import com.company.aidev.tool.FileTools;
import com.company.aidev.tool.GitTools;
import com.company.aidev.tool.MavenTools;
import com.company.aidev.tool.ToolContext;
import com.company.aidev.tool.ToolExecutionRecorder;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Runs the tests and analyses coverage gaps.
 *
 * <p>The split with the developer agent is deliberate and goes further than prompting: the counts in
 * {@link TestReport} come from {@link MavenOutputParser} reading the real build log, never from the
 * model. The model is only asked what it is good at — which acceptance criteria have no test, and
 * what should be added.
 */
@Component
public class TestAgent {

    private static final Logger log = LoggerFactory.getLogger(TestAgent.class);
    private static final int MAX_DIFF_CHARS = 40_000;

    private final AgentSupport agentSupport;
    private final PromptLoader promptLoader;
    private final SandboxManager sandboxManager;
    private final GitOperations gitOperations;
    private final MavenOutputParser outputParser;
    private final ToolExecutionRecorder toolRecorder;
    private final SandboxProperties sandboxProperties;

    public TestAgent(
            AgentSupport agentSupport,
            PromptLoader promptLoader,
            SandboxManager sandboxManager,
            GitOperations gitOperations,
            MavenOutputParser outputParser,
            ToolExecutionRecorder toolRecorder,
            SandboxProperties sandboxProperties) {
        this.agentSupport = agentSupport;
        this.promptLoader = promptLoader;
        this.sandboxManager = sandboxManager;
        this.gitOperations = gitOperations;
        this.outputParser = outputParser;
        this.toolRecorder = toolRecorder;
        this.sandboxProperties = sandboxProperties;
    }

    /**
     * Executes the build and, when it is green, asks the agent for missing test cases.
     *
     * @param attempt current development attempt, for audit correlation
     */
    public TestReport runAndAnalyse(
            UUID workflowId, Sandbox sandbox, int attempt, TicketAnalysis analysis, RepositoryRules rules) {

        TestReport report = runBuild(sandbox);
        if (!report.successful()) {
            log.info(
                    "Build red for {} on attempt {}: {} failing test(s)",
                    analysis.ticketId(),
                    attempt,
                    report.failedTests());
            // A red build already tells the developer agent everything it needs; spending a reasoning
            // call to restate the failures would only add latency and a chance of hallucination.
            return report;
        }

        List<String> missing = analyseCoverageGaps(workflowId, sandbox, attempt, analysis, rules);

        // The agent may have written new tests. Re-run the build so the reported result always
        // describes the workspace as it will be committed, not as it was before the gap analysis.
        TestReport finalReport = runBuild(sandbox);
        log.info(
                "Tests for {} attempt {}: {}/{} passed, {} coverage gap(s) reported",
                analysis.ticketId(),
                attempt,
                finalReport.totalTests() - finalReport.failedTests(),
                finalReport.totalTests(),
                missing.size());

        return new TestReport(
                finalReport.successful(),
                finalReport.totalTests(),
                finalReport.failedTests(),
                finalReport.skippedTests(),
                finalReport.failures(),
                missing,
                finalReport.rawOutputExcerpt());
    }

    /** Runs {@code mvn verify} (or the wrapper) and parses the log. */
    TestReport runBuild(Sandbox sandbox) {
        boolean hasWrapper = sandboxManager.exists(sandbox, "mvnw");
        List<String> command = List.of(hasWrapper ? "./mvnw" : "mvn", "-B", "-ntp", "verify");

        CommandResult result = sandboxManager.execute(
                sandbox, command, sandbox.repositoryPath(), sandboxProperties.commandTimeout());

        if (result.timedOut()) {
            return TestReport.failed(
                    result.toToolOutput(20_000),
                    "The build exceeded the " + sandboxProperties.commandTimeout() + " timeout");
        }
        return outputParser.parse(result.combinedOutput(), result.successful());
    }

    private List<String> analyseCoverageGaps(
            UUID workflowId, Sandbox sandbox, int attempt, TicketAnalysis analysis, RepositoryRules rules) {

        AgentExecutionEntity execution = agentSupport.beginExecution(AgentType.TEST, workflowId, attempt);
        ToolContext toolContext = new ToolContext(workflowId, execution.getId(), sandbox);

        List<Object> tools = List.of(
                new FileTools(sandboxManager, toolRecorder, toolContext),
                new MavenTools(sandboxManager, toolRecorder, toolContext),
                new GitTools(gitOperations, toolRecorder, toolContext));

        String diff = gitOperations.diff(sandbox, MAX_DIFF_CHARS);
        String systemPrompt = promptLoader.load("test");
        String userPrompt = """
                # Ticket %s
                Objective: %s

                ## Acceptance criteria
                %s

                ## Diff of the change (tests are green at this point)
                ```diff
                %s
                ```

                %s

                Add the tests you consider missing with your tools, re-run them, then answer with the
                JSON object described in your instructions.
                """
                .formatted(
                        analysis.ticketId(),
                        analysis.objective(),
                        numbered(analysis.acceptanceCriteria()),
                        diff.isBlank() ? "(no diff available)" : diff,
                        rules.render(RepositoryRules.TESTING_GUIDELINES, RepositoryRules.COMMANDS));

        AgentRequest request = new AgentRequest(AgentType.TEST, workflowId, attempt, systemPrompt, userPrompt, tools);

        try {
            CoverageAnalysis coverage = agentSupport.execute(request, execution, CoverageAnalysis.class);
            return coverage.missingTestCases();
        } catch (AgentExecutionException e) {
            // Gap analysis is advisory: failing it must not turn a green build into a failed workflow.
            log.warn("Coverage analysis failed for {}, continuing with a green build: {}", analysis.ticketId(), e.toString());
            return List.of();
        }
    }

    private static String numbered(List<String> items) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < items.size(); i++) {
            sb.append("AC").append(i + 1).append(": ").append(items.get(i)).append('\n');
        }
        return sb.isEmpty() ? "(none)" : sb.toString();
    }

    /** Model answer of the coverage analysis pass. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CoverageAnalysis(List<String> missingTestCases, List<String> addedTests, String summary) {
        public CoverageAnalysis {
            missingTestCases = missingTestCases == null ? List.of() : List.copyOf(missingTestCases);
            addedTests = addedTests == null ? List.of() : List.copyOf(addedTests);
        }
    }

    /** Exposed for the engine, which re-runs the build after the test agent added tests. */
    public Duration buildTimeout() {
        return sandboxProperties.commandTimeout();
    }
}
