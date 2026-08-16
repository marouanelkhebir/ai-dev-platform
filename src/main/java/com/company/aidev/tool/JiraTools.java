package com.company.aidev.tool;

import com.company.aidev.jira.JiraClient;
import com.company.aidev.jira.model.JiraIssue;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import java.util.UUID;

/**
 * Read-only Jira tools.
 *
 * <p>Commenting and status changes are administrative actions: they are performed by the workflow
 * engine at well-defined points, not by an agent that decides mid-reasoning that a ticket should
 * move. Only reading is exposed here.
 */
public class JiraTools {

    private final JiraClient jiraClient;
    private final ToolExecutionRecorder recorder;
    private final UUID workflowId;
    private final UUID agentExecutionId;

    public JiraTools(JiraClient jiraClient, ToolExecutionRecorder recorder, UUID workflowId, UUID agentExecutionId) {
        this.jiraClient = jiraClient;
        this.recorder = recorder;
        this.workflowId = workflowId;
        this.agentExecutionId = agentExecutionId;
    }

    @Tool("Fetch a Jira issue: summary, description, acceptance criteria, labels, priority, comments and linked issues.")
    public String getJiraIssue(@P("Jira issue key, e.g. BANK-1245") String issueKey) {
        return recorder.record(workflowId, agentExecutionId, "getJiraIssue", issueKey, () -> {
            JiraIssue issue = jiraClient.getIssue(issueKey);
            return issue.renderForPrompt();
        });
    }
}
