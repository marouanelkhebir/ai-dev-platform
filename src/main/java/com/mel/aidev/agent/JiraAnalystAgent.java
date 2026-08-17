package com.mel.aidev.agent;

import com.mel.aidev.domain.TicketAnalysis;
import com.mel.aidev.domain.RepositoryContext;
import com.mel.aidev.gitlab.GitLabClient;
import com.mel.aidev.jira.JiraClient;
import com.mel.aidev.jira.model.JiraIssue;
import com.mel.aidev.llm.PromptLoader;
import com.mel.aidev.persistence.entity.AgentExecutionEntity;
import com.mel.aidev.tool.JiraTools;
import com.mel.aidev.tool.RepositoryReadTools;
import com.mel.aidev.tool.ToolExecutionRecorder;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Reads a Jira ticket and turns it into a structured, verifiable analysis.
 *
 * <p>This agent is the gate of the whole platform. It derives a proportionate, observable
 * verification criterion from an explicit request when the ticket has no formal acceptance criteria,
 * but reports genuinely missing business rules as ambiguities.
 */
@Component
public class JiraAnalystAgent {

    private static final Logger log = LoggerFactory.getLogger(JiraAnalystAgent.class);

    private final AgentSupport agentSupport;
    private final PromptLoader promptLoader;
    private final JiraClient jiraClient;
    private final GitLabClient gitLabClient;
    private final ToolExecutionRecorder toolRecorder;

    public JiraAnalystAgent(
            AgentSupport agentSupport,
            PromptLoader promptLoader,
            JiraClient jiraClient,
            GitLabClient gitLabClient,
            ToolExecutionRecorder toolRecorder) {
        this.agentSupport = agentSupport;
        this.promptLoader = promptLoader;
        this.jiraClient = jiraClient;
        this.gitLabClient = gitLabClient;
        this.toolRecorder = toolRecorder;
    }

    public TicketAnalysis analyze(UUID workflowId, JiraIssue issue) {
        return analyze(workflowId, issue, null);
    }

    public TicketAnalysis analyze(UUID workflowId, JiraIssue issue, String clarification) {
        return analyze(workflowId, issue, null, clarification);
    }

    /**
     * Analyses a ticket with the target repository available as read-only evidence.
     *
     * <p>This happens before clarification is requested: code can settle technical scope questions
     * (for example the template that owns a page title) without asking a human.
     */
    public TicketAnalysis analyze(UUID workflowId, JiraIssue issue, RepositoryContext repository, String clarification) {
        AgentExecutionEntity execution = agentSupport.beginExecution(AgentType.JIRA_ANALYST, workflowId, 1);

        JiraTools jiraTools = new JiraTools(jiraClient, toolRecorder, workflowId, execution.getId());
        String systemPrompt = promptLoader.load("jira-analyst");
        String userPrompt = buildUserPrompt(issue, repository, clarification);

        AgentRequest request = new AgentRequest(AgentType.JIRA_ANALYST, workflowId, 1, systemPrompt, userPrompt,
                repository == null ? List.of(jiraTools) : List.of(jiraTools, repositoryTools(repository, workflowId, execution)));

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

    /** Analyses a direct request using the same structured contract as Jira-backed workflows. */
    public TicketAnalysis analyzeMessage(UUID workflowId, String requestId, String message) {
        return analyzeMessage(workflowId, requestId, message, null);
    }

    public TicketAnalysis analyzeMessage(UUID workflowId, String requestId, String message, String clarification) {
        return analyzeMessage(workflowId, requestId, message, null, clarification);
    }

    /** Analyses a direct request after retrieving the target repository in read-only mode. */
    public TicketAnalysis analyzeMessage(
            UUID workflowId, String requestId, String message, RepositoryContext repository, String clarification) {
        AgentExecutionEntity execution = agentSupport.beginExecution(AgentType.JIRA_ANALYST, workflowId, 1);
        AgentRequest request = new AgentRequest(
                AgentType.JIRA_ANALYST,
                workflowId,
                1,
                promptLoader.load("jira-analyst"),
                """
                Analyse the following direct development request. It does not have a Jira issue.

                Request identifier: %s

                ## Request
                %s

                %s

                %s

                The repository tools are read-only. Use them to establish the concrete implementation
                scope before reporting an ambiguity. Do not use Jira tools. Answer with the JSON object
                described in your instructions.
                """.formatted(requestId, message, repositorySection(repository), clarificationSection(clarification)),
                repository == null ? List.of() : List.of(repositoryTools(repository, workflowId, execution)));
        TicketAnalysis analysis = agentSupport.execute(request, execution, TicketAnalysis.class);
        return new TicketAnalysis(
                requestId,
                analysis.objective(),
                analysis.acceptanceCriteria(),
                analysis.impactedServices(),
                analysis.ambiguities(),
                analysis.riskLevel(),
                analysis.summaryForDeveloper());
    }

    private String buildUserPrompt(JiraIssue issue, RepositoryContext repository, String clarification) {
        return """
                Analyse the following Jira ticket.

                %s

                %s

                %s

                If linked issues carry information you need, fetch them with the getJiraIssue tool. The repository
                tools are read-only: use them to establish concrete technical scope before reporting an ambiguity.
                Answer with the JSON object described in your instructions.
                """
                .formatted(issue.renderForPrompt(), repositorySection(repository), clarificationSection(clarification));
    }

    private RepositoryReadTools repositoryTools(
            RepositoryContext repository, UUID workflowId, AgentExecutionEntity execution) {
        return new RepositoryReadTools(
                gitLabClient,
                toolRecorder,
                workflowId,
                execution.getId(),
                repository.projectId(),
                repository.defaultBranch());
    }

    private String repositorySection(RepositoryContext repository) {
        if (repository == null) {
            return "";
        }
        return """
                ## Target repository (retrieved before this analysis)
                %s
                """.formatted(repository.renderForPrompt(250));
    }

    private String clarificationSection(String clarification) {
        if (clarification == null || clarification.isBlank()) {
            return "";
        }
        return """
                ## Human clarification
                This information answers a previous analysis question. Treat it as authoritative context for this workflow.
                %s
                """.formatted(clarification);
    }
}
