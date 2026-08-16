package com.company.aidev.jira.model;

import java.util.List;

/**
 * Typed view of a Jira issue.
 *
 * <p>The agents receive this object, never the raw Jira JSON: the mapping quirks (ADF descriptions,
 * custom fields, link directions) belong to the client layer.
 */
public record JiraIssue(
        String key,
        String summary,
        String description,
        List<String> acceptanceCriteria,
        List<JiraComment> comments,
        List<String> labels,
        String priority,
        String status,
        String issueType,
        List<JiraIssueLink> links,
        String assignee,
        String reporter) {

    public JiraIssue {
        acceptanceCriteria = acceptanceCriteria == null ? List.of() : List.copyOf(acceptanceCriteria);
        comments = comments == null ? List.of() : List.copyOf(comments);
        labels = labels == null ? List.of() : List.copyOf(labels);
        links = links == null ? List.of() : List.copyOf(links);
    }

    public boolean hasLabel(String label) {
        return labels.stream().anyMatch(l -> l.equalsIgnoreCase(label));
    }

    /** Markdown rendering handed to the Jira analyst agent. */
    public String renderForPrompt() {
        StringBuilder sb = new StringBuilder();
        sb.append("# Jira ").append(key).append(" — ").append(summary).append('\n');
        sb.append("Type: ").append(issueType).append(" | Status: ").append(status).append(" | Priority: ")
                .append(priority).append('\n');
        if (!labels.isEmpty()) {
            sb.append("Labels: ").append(String.join(", ", labels)).append('\n');
        }
        sb.append("\n## Description\n").append(description == null || description.isBlank() ? "(empty)" : description)
                .append('\n');

        if (!acceptanceCriteria.isEmpty()) {
            sb.append("\n## Acceptance criteria (from the dedicated Jira field)\n");
            for (int i = 0; i < acceptanceCriteria.size(); i++) {
                sb.append("AC").append(i + 1).append(": ").append(acceptanceCriteria.get(i)).append('\n');
            }
        }

        if (!links.isEmpty()) {
            sb.append("\n## Linked issues\n");
            links.forEach(link -> sb.append("- ")
                    .append(link.type())
                    .append(" (")
                    .append(link.direction())
                    .append(") ")
                    .append(link.issueKey())
                    .append(" [")
                    .append(link.status())
                    .append("]: ")
                    .append(link.summary())
                    .append('\n'));
        }

        if (!comments.isEmpty()) {
            sb.append("\n## Comments\n");
            comments.forEach(comment -> sb.append("### ")
                    .append(comment.author())
                    .append(" (")
                    .append(comment.created())
                    .append(")\n")
                    .append(comment.body())
                    .append('\n'));
        }
        return sb.toString();
    }
}
