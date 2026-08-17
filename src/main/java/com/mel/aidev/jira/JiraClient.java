package com.mel.aidev.jira;

import com.mel.aidev.jira.model.JiraIssue;
import com.mel.aidev.jira.model.JiraTransition;
import java.util.List;

/**
 * Typed Jira access.
 *
 * <p>Agents never build HTTP requests: they call tools, tools call this client. That is what keeps a
 * hallucinated URL from becoming a real API call.
 */
public interface JiraClient {

    JiraIssue getIssue(String issueKey);

    /**
     * Whether a Jira project with this key exists.
     *
     * <p>Used when a project is saved: a typo in the key would otherwise only surface when the first
     * ticket of the project is refused as belonging to another one.
     */
    boolean projectExists(String projectKey);

    void addComment(String issueKey, String comment);

    /** Adds a label to an issue without replacing its existing labels. */
    void addLabel(String issueKey, String label);

    List<JiraTransition> getTransitions(String issueKey);

    /**
     * Moves the issue to the given status by finding the matching transition.
     *
     * @return true when the transition was applied, false when no matching transition exists
     */
    boolean transitionTo(String issueKey, String targetStatusName);
}
