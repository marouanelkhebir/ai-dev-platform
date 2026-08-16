package com.company.aidev.rules;

import com.company.aidev.domain.RepositoryContext;
import com.company.aidev.domain.RepositoryRules;
import com.company.aidev.gitlab.GitLabClient;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Loads the read-only context of a repository: the {@code .ai/} rules, the README, the build file and
 * the OpenAPI specifications.
 *
 * <p>The {@code .ai/} directory is how a team tells the agents how its repository works. It is read
 * on every run and never written: an agent that could edit its own rules would be able to remove its
 * own constraints.
 */
@Component
public class RepositoryRulesLoader {

    private static final Logger log = LoggerFactory.getLogger(RepositoryRulesLoader.class);

    private static final String RULES_DIRECTORY = ".ai/";

    private static final List<String> RULE_FILES = List.of(
            RepositoryRules.AGENT_INSTRUCTIONS,
            RepositoryRules.ARCHITECTURE,
            RepositoryRules.DOMAIN,
            RepositoryRules.CODING_GUIDELINES,
            RepositoryRules.TESTING_GUIDELINES,
            RepositoryRules.SECURITY,
            RepositoryRules.COMMANDS);

    private static final List<String> README_CANDIDATES = List.of("README.md", "readme.md", "README.adoc");
    private static final List<String> BUILD_FILE_CANDIDATES = List.of("pom.xml", "build.gradle", "build.gradle.kts");

    private static final int MAX_RULE_CHARS = 30_000;
    private static final int MAX_README_CHARS = 20_000;
    private static final int MAX_BUILD_FILE_CHARS = 20_000;
    private static final int MAX_TREE_ENTRIES = 1_500;

    private final GitLabClient gitLabClient;

    public RepositoryRulesLoader(GitLabClient gitLabClient) {
        this.gitLabClient = gitLabClient;
    }

    public RepositoryRules loadRules(String projectId, String ref) {
        Map<String, String> documents = new LinkedHashMap<>();
        for (String fileName : RULE_FILES) {
            gitLabClient
                    .readFile(projectId, ref, RULES_DIRECTORY + fileName)
                    .filter(content -> !content.isBlank())
                    .ifPresent(content -> documents.put(fileName, truncate(content, MAX_RULE_CHARS)));
        }
        if (documents.isEmpty()) {
            log.info("No .ai/ rules found in {} at {}; agents will use the platform defaults", projectId, ref);
        } else {
            log.info("Loaded {} rule file(s) from {}/.ai at {}", documents.size(), projectId, ref);
        }
        return new RepositoryRules(documents);
    }

    public RepositoryContext loadContext(String projectId, String ref) {
        List<String> tree = gitLabClient.listRepositoryFiles(projectId, ref, MAX_TREE_ENTRIES);

        String readme = README_CANDIDATES.stream()
                .map(candidate -> gitLabClient.readFile(projectId, ref, candidate))
                .filter(java.util.Optional::isPresent)
                .map(java.util.Optional::get)
                .findFirst()
                .map(content -> truncate(content, MAX_README_CHARS))
                .orElse("");

        String buildFile = BUILD_FILE_CANDIDATES.stream()
                .map(candidate -> gitLabClient.readFile(projectId, ref, candidate))
                .filter(java.util.Optional::isPresent)
                .map(java.util.Optional::get)
                .findFirst()
                .map(content -> truncate(content, MAX_BUILD_FILE_CHARS))
                .orElse("");

        List<String> openApiSpecs = tree.stream()
                .filter(RepositoryRulesLoader::looksLikeOpenApi)
                .limit(10)
                .toList();

        return new RepositoryContext(
                projectId, ref, tree, readme, buildFile, openApiSpecs, loadRules(projectId, ref));
    }

    private static boolean looksLikeOpenApi(String path) {
        String lower = path.toLowerCase(java.util.Locale.ROOT);
        boolean isSpecFile = lower.endsWith(".yaml") || lower.endsWith(".yml") || lower.endsWith(".json");
        return isSpecFile && (lower.contains("openapi") || lower.contains("swagger") || lower.contains("api-spec"));
    }

    private static String truncate(String content, int maxChars) {
        return content.length() <= maxChars ? content : content.substring(0, maxChars) + "\n...[truncated]";
    }
}
