package com.mel.aidev.jira.model;

/**
 * A link to another issue.
 *
 * @param type link type, e.g. {@code blocks} or {@code relates to}
 * @param direction {@code inward} or {@code outward}
 * @param issueKey linked issue key
 * @param summary linked issue summary
 * @param status linked issue status
 */
public record JiraIssueLink(String type, String direction, String issueKey, String summary, String status) {}
