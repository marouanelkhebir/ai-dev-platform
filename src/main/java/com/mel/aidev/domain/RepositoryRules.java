package com.mel.aidev.domain;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Content of the {@code .ai/} directory of a repository.
 *
 * <p>These files are read-only inputs. The agents must never rewrite them: the rules belong to the
 * humans owning the repository.
 */
public record RepositoryRules(Map<String, String> documents) {

    public static final String ARCHITECTURE = "architecture.md";
    public static final String CODING_GUIDELINES = "coding-guidelines.md";
    public static final String TESTING_GUIDELINES = "testing-guidelines.md";
    public static final String DOMAIN = "domain.md";
    public static final String SECURITY = "security.md";
    public static final String COMMANDS = "commands.md";
    public static final String AGENT_INSTRUCTIONS = "agent-instructions.md";

    public RepositoryRules {
        documents = documents == null ? Map.of() : new LinkedHashMap<>(documents);
    }

    public static RepositoryRules empty() {
        return new RepositoryRules(Map.of());
    }

    public String get(String name) {
        return documents.getOrDefault(name, "");
    }

    public boolean isEmpty() {
        return documents.isEmpty();
    }

    /** Renders the selected documents as a single prompt section, in a stable order. */
    public String render(String... names) {
        StringBuilder sb = new StringBuilder();
        for (String name : names) {
            String content = documents.get(name);
            if (content != null && !content.isBlank()) {
                sb.append("### .ai/").append(name).append('\n').append(content).append("\n\n");
            }
        }
        return sb.toString();
    }

    public String renderAll() {
        return render(
                AGENT_INSTRUCTIONS, ARCHITECTURE, DOMAIN, CODING_GUIDELINES, TESTING_GUIDELINES, SECURITY, COMMANDS);
    }
}
