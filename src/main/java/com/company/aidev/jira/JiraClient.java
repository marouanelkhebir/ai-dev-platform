package com.company.aidev.jira;

import com.company.aidev.jira.model.JiraIssue;
import com.company.aidev.jira.model.JiraTransition;
import java.util.List;

/**
 * Typed Jira access.
 *
 * <p>Agents never build HTTP requests: they call tools, tools call this client. That is what keeps a
 * hallucinated URL from becoming a real API call.
 */
public interface JiraClient {

    JiraIssue getIssue(String issueKey);

    void addComment(String issueKey, String comment);

    List<JiraTransition> getTransitions(String issueKey);

    /**
     * Moves the issue to the given status by finding the matching transition.
     *
     * @return true when the transition was applied, false when no matching transition exists
     */
    boolean transitionTo(String issueKey, String targetStatusName);
}
