package com.company.aidev.agent;

import com.company.aidev.domain.RepositoryContext;
import com.company.aidev.domain.TechnicalPlan;
import com.company.aidev.domain.TicketAnalysis;
import com.company.aidev.gitlab.GitLabClient;
import com.company.aidev.llm.PromptLoader;
import com.company.aidev.persistence.entity.AgentExecutionEntity;
import com.company.aidev.tool.RepositoryReadTools;
import com.company.aidev.tool.ToolExecutionRecorder;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Produces the implementation plan.
 *
 * <p>The architect explores the repository through read-only GitLab tools and never receives a
 * sandbox: it cannot write even by accident. Separating planning from writing also produces a
 * reviewable artefact — the plan is stored and shown in the merge request.
 */
@Component
public class ArchitectAgent {

    private static final Logger log = LoggerFactory.getLogger(ArchitectAgent.class);
    private static final int MAX_TREE_ENTRIES = 250;

    private final AgentSupport agentSupport;
    private final PromptLoader promptLoader;
    private final GitLabClient gitLabClient;
    private final ToolExecutionRecorder toolRecorder;

    public ArchitectAgent(
            AgentSupport agentSupport,
            PromptLoader promptLoader,
            GitLabClient gitLabClient,
            ToolExecutionRecorder toolRecorder) {
        this.agentSupport = agentSupport;
        this.promptLoader = promptLoader;
        this.gitLabClient = gitLabClient;
        this.toolRecorder = toolRecorder;
    }

    public TechnicalPlan plan(UUID workflowId, TicketAnalysis analysis, RepositoryContext repository) {
        AgentExecutionEntity execution = agentSupport.beginExecution(AgentType.ARCHITECT, workflowId, 1);

        RepositoryReadTools readTools = new RepositoryReadTools(
                gitLabClient,
                toolRecorder,
                workflowId,
                execution.getId(),
                repository.projectId(),
                repository.defaultBranch());

        String systemPrompt = promptLoader.load("architect");
        String userPrompt = """
                ## Ticket analysis
                Ticket: %s
                Objective: %s
                Risk: %s

                ### Acceptance criteria
                %s

                ### Services expected to be impacted
                %s

                %s

                Explore the repository with your tools before answering. Answer with the JSON object
                described in your instructions.
                """
                .formatted(
                        analysis.ticketId(),
                        analysis.objective(),
                        analysis.riskLevel(),
                        numbered(analysis.acceptanceCriteria()),
                        analysis.impactedServices().isEmpty() ? "(unknown)" : String.join(", ", analysis.impactedServices()),
                        repository.renderForPrompt(MAX_TREE_ENTRIES));

        AgentRequest request =
                new AgentRequest(AgentType.ARCHITECT, workflowId, 1, systemPrompt, userPrompt, List.of(readTools));

        TechnicalPlan plan = agentSupport.execute(request, execution, TechnicalPlan.class);
        if (plan.isEmpty()) {
            throw new AgentExecutionException(
                    AgentType.ARCHITECT, "The architect produced an empty plan for " + analysis.ticketId(), null);
        }
        log.info(
                "Plan for {}: {} file(s) to change, {} step(s), {} test(s) to add",
                analysis.ticketId(),
                plan.filesToModify().size(),
                plan.implementationSteps().size(),
                plan.testsToAdd().size());
        return plan;
    }

    private static String numbered(List<String> items) {
        if (items.isEmpty()) {
            return "(none)";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < items.size(); i++) {
            sb.append("AC").append(i + 1).append(": ").append(items.get(i)).append('\n');
        }
        return sb.toString();
    }
}
