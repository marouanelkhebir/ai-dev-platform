package com.company.aidev.agent;

import com.company.aidev.domain.TicketAnalysis;
import com.company.aidev.jira.JiraClient;
import com.company.aidev.jira.model.JiraIssue;
import com.company.aidev.llm.PromptLoader;
import com.company.aidev.persistence.entity.AgentExecutionEntity;
import com.company.aidev.tool.JiraTools;
import com.company.aidev.tool.ToolExecutionRecorder;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Reads a Jira ticket and turns it into a structured, verifiable analysis.
 *
 * <p>This agent is the gate of the whole platform: if it cannot state the objective and the
 * acceptance criteria from the ticket alone, no amount of downstream cleverness will produce a
 * correct change. It is therefore instructed to report ambiguities rather than resolve them, and the
 * engine stops the workflow when it does.
 */
@Component
public class JiraAnalystAgent {

    private static final Logger log = LoggerFactory.getLogger(JiraAnalystAgent.class);

    private final AgentSupport agentSupport;
    private final PromptLoader promptLoader;
    private final JiraClient jiraClient;
    private final ToolExecutionRecorder toolRecorder;

    public JiraAnalystAgent(
            AgentSupport agentSupport,
            PromptLoader promptLoader,
            JiraClient jiraClient,
            ToolExecutionRecorder toolRecorder) {
        this.agentSupport = agentSupport;
        this.promptLoader = promptLoader;
        this.jiraClient = jiraClient;
        this.toolRecorder = toolRecorder;
    }

    public TicketAnalysis analyze(UUID workflowId, JiraIssue issue) {
        AgentExecutionEntity execution = agentSupport.beginExecution(AgentType.JIRA_ANALYST, workflowId, 1);

        JiraTools jiraTools = new JiraTools(jiraClient, toolRecorder, workflowId, execution.getId());
        String systemPrompt = promptLoader.load("jira-analyst");
        String userPrompt = buildUserPrompt(issue);

        AgentRequest request = new AgentRequest(
                AgentType.JIRA_ANALYST, workflowId, 1, systemPrompt, userPrompt, List.of(jiraTools));

        TicketAnalysis analysis = agentSupport.execute(request, execution, TicketAnalysis.class);

        // The model is asked to echo the ticket id; if it drifts, the platform value wins.
        TicketAnalysis normalized = new TicketAnalysis(
                issue.key(),
                analysis.objective(),
                analysis.acceptanceCriteria().isEmpty() ? issue.acceptanceCriteria() : analysis.acceptanceCriteria(),
                analysis.impactedServices(),
                analysis.ambiguities(),
                analysis.riskLevel(),
                analysis.summaryForDeveloper());

        log.info(
                "Ticket {} analysed: {} acceptance criteria, {} ambiguities, risk {}",
                issue.key(),
                normalized.acceptanceCriteria().size(),
                normalized.ambiguities().size(),
                normalized.riskLevel());
        return normalized;
    }

    private String buildUserPrompt(JiraIssue issue) {
        return """
                Analyse the following Jira ticket.

                %s

                If linked issues carry information you need, fetch them with the getJiraIssue tool.
                Answer with the JSON object described in your instructions.
                """
                .formatted(issue.renderForPrompt());
    }
}
