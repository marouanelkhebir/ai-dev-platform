package com.company.aidev.domain;

import java.util.List;

/**
 * Read-only view of a repository handed to the architect agent.
 *
 * <p>Source priority is enforced here by construction order: Jira acceptance criteria first (they
 * live in {@link TicketAnalysis}), then the current code, then {@code .ai/*}, then OpenAPI/ADR, then
 * general documentation.
 */
public record RepositoryContext(
        String projectId,
        String defaultBranch,
        List<String> fileTree,
        String readme,
        String buildFileExcerpt,
        List<String> openApiSpecs,
        RepositoryRules rules) {

    public RepositoryContext {
        fileTree = fileTree == null ? List.of() : List.copyOf(fileTree);
        openApiSpecs = openApiSpecs == null ? List.of() : List.copyOf(openApiSpecs);
        rules = rules == null ? RepositoryRules.empty() : rules;
        readme = readme == null ? "" : readme;
        buildFileExcerpt = buildFileExcerpt == null ? "" : buildFileExcerpt;
    }

    public String renderForPrompt(int maxTreeEntries) {
        StringBuilder sb = new StringBuilder();
        sb.append("## Repository: ").append(projectId).append(" (default branch: ").append(defaultBranch).append(")\n");
        sb.append("### File tree (truncated to ").append(maxTreeEntries).append(" entries)\n");
        fileTree.stream().limit(maxTreeEntries).forEach(f -> sb.append(f).append('\n'));
        if (fileTree.size() > maxTreeEntries) {
            sb.append("... (").append(fileTree.size() - maxTreeEntries).append(" more)\n");
        }
        if (!readme.isBlank()) {
            sb.append("\n### README\n").append(readme).append('\n');
        }
        if (!buildFileExcerpt.isBlank()) {
            sb.append("\n### Build file\n").append(buildFileExcerpt).append('\n');
        }
        if (!openApiSpecs.isEmpty()) {
            sb.append("\n### OpenAPI specifications found\n");
            openApiSpecs.forEach(s -> sb.append("- ").append(s).append('\n'));
        }
        if (!rules.isEmpty()) {
            sb.append("\n## Repository rules (.ai/)\n").append(rules.renderAll());
        }
        return sb.toString();
    }
}
