package com.company.aidev.agent;

import com.company.aidev.domain.DevelopmentResult;
import com.company.aidev.domain.BuildProfile;
import com.company.aidev.domain.RepositoryRules;
import com.company.aidev.domain.TechnicalPlan;
import com.company.aidev.domain.TicketAnalysis;
import com.company.aidev.git.GitOperations;
import com.company.aidev.llm.PromptLoader;
import com.company.aidev.persistence.entity.AgentExecutionEntity;
import com.company.aidev.sandbox.Sandbox;
import com.company.aidev.sandbox.SandboxManager;
import com.company.aidev.tool.FileTools;
import com.company.aidev.tool.GitTools;
import com.company.aidev.tool.MavenTools;
import com.company.aidev.tool.NpmTools;
import com.company.aidev.tool.PythonTools;
import com.company.aidev.tool.ToolContext;
import com.company.aidev.tool.ToolExecutionRecorder;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Implements the technical plan inside the sandbox.
 *
 * <p>This is the only agent with write access, and that access is bounded on three sides: it can
 * only write inside its own container, only through {@link FileTools} (which rejects path traversal,
 * {@code .git} and {@code .ai}), and only for the duration of one ticket.
 *
 * <p>It has no git commit, git push or Jira tool. Turning its work into a commit is the engine's
 * decision, taken after the tests have actually passed.
 */
@Component
public class DeveloperAgent {

    private static final Logger log = LoggerFactory.getLogger(DeveloperAgent.class);

    private final AgentSupport agentSupport;
    private final PromptLoader promptLoader;
    private final SandboxManager sandboxManager;
    private final GitOperations gitOperations;
    private final ToolExecutionRecorder toolRecorder;

    public DeveloperAgent(
            AgentSupport agentSupport,
            PromptLoader promptLoader,
            SandboxManager sandboxManager,
            GitOperations gitOperations,
            ToolExecutionRecorder toolRecorder) {
        this.agentSupport = agentSupport;
        this.promptLoader = promptLoader;
        this.sandboxManager = sandboxManager;
        this.gitOperations = gitOperations;
        this.toolRecorder = toolRecorder;
    }

    /**
     * Runs one implementation attempt.
     *
     * @param feedback output of the previous attempt (test failures, review findings, CI errors), or
     *     {@code null} for the first attempt
     */
    public DevelopmentResult implement(
            UUID workflowId,
            Sandbox sandbox,
            int attempt,
            TicketAnalysis analysis,
            TechnicalPlan plan,
            RepositoryRules rules,
            String feedback) {

        AgentExecutionEntity execution = agentSupport.beginExecution(AgentType.DEVELOPER, workflowId, attempt);
        ToolContext toolContext = new ToolContext(workflowId, execution.getId(), sandbox);

        List<Object> tools = new java.util.ArrayList<>();
        tools.add(new FileTools(sandboxManager, toolRecorder, toolContext));
        BuildProfile profile = BuildProfile.detect(sandboxManager, sandbox);
        if (profile == BuildProfile.ANGULAR) {
            tools.add(new NpmTools(sandboxManager, toolRecorder, toolContext));
        } else if (profile == BuildProfile.PYTHON) {
            tools.add(new PythonTools(sandboxManager, toolRecorder, toolContext));
        } else {
            tools.add(new MavenTools(sandboxManager, toolRecorder, toolContext));
        }
        tools.add(new GitTools(gitOperations, toolRecorder, toolContext));

        String systemPrompt = promptLoader.load("developer");
        String userPrompt = buildUserPrompt(analysis, plan, rules, feedback, attempt);

        AgentRequest request =
                new AgentRequest(AgentType.DEVELOPER, workflowId, attempt, systemPrompt, userPrompt, tools);

        DevelopmentResult result = agentSupport.execute(request, execution, DevelopmentResult.class);
        log.info(
                "Developer attempt {} for {}: completed={} changedFiles={}",
                attempt,
                analysis.ticketId(),
                result.completed(),
                result.changedFiles().size());
        return result;
    }

    private String buildUserPrompt(
            TicketAnalysis analysis, TechnicalPlan plan, RepositoryRules rules, String feedback, int attempt) {

        StringBuilder sb = new StringBuilder();
        sb.append("# Ticket ").append(analysis.ticketId()).append('\n');
        sb.append("Objective: ").append(analysis.objective()).append("\n\n");

        sb.append("## Acceptance criteria (these define \"done\")\n");
        for (int i = 0; i < analysis.acceptanceCriteria().size(); i++) {
            sb.append("AC").append(i + 1).append(": ").append(analysis.acceptanceCriteria().get(i)).append('\n');
        }

        sb.append("\n## Technical plan produced by the architect\n");
        sb.append("### Files to change\n");
        plan.filesToModify()
                .forEach(file -> sb.append("- [")
                        .append(file.changeType())
                        .append("] ")
                        .append(file.path())
                        .append(" — ")
                        .append(file.reason())
                        .append('\n'));
        sb.append("\n### Implementation steps\n");
        for (int i = 0; i < plan.implementationSteps().size(); i++) {
            sb.append(i + 1).append(". ").append(plan.implementationSteps().get(i)).append('\n');
        }
        sb.append("\n### Tests to add\n");
        plan.testsToAdd().forEach(test -> sb.append("- ").append(test).append('\n'));

        if (!plan.technicalRisks().isEmpty()) {
            sb.append("\n### Risks flagged by the architect\n");
            plan.technicalRisks().forEach(risk -> sb.append("- ").append(risk).append('\n'));
        }

        String rulesSection = rules.render(
                RepositoryRules.AGENT_INSTRUCTIONS,
                RepositoryRules.CODING_GUIDELINES,
                RepositoryRules.TESTING_GUIDELINES,
                RepositoryRules.ARCHITECTURE,
                RepositoryRules.COMMANDS);
        if (!rulesSection.isBlank()) {
            sb.append("\n## Repository rules you must follow\n").append(rulesSection);
        }

        if (feedback != null && !feedback.isBlank()) {
            sb.append("\n## Attempt ")
                    .append(attempt)
                    .append(" — what went wrong last time\n")
                    .append(feedback)
                    .append("\nFix these problems. Do not start over: the workspace still contains your previous work.\n");
        }

        sb.append("\nWork in the repository with your tools, then answer with the JSON object described in your instructions.\n");
        return sb.toString();
    }
}
