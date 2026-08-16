package com.company.aidev.config;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * GitLab connection and safety configuration.
 *
 * <p>The token used here belongs to the dedicated {@code gitlab-ai-bot} technical account and must
 * not carry maintainer rights on protected branches.
 */
@ConfigurationProperties(prefix = "gitlab")
public record GitLabProperties(
        String baseUrl,
        String apiToken,
        Duration connectTimeout,
        Duration readTimeout,
        String branchPrefix,
        String defaultTargetBranch,
        List<String> protectedBranches,
        String mergeRequestLabel,
        String botName,
        String botEmail,
        Webhook webhook,
        Pipeline pipeline) {

    public GitLabProperties {
        botName = botName == null || botName.isBlank() ? "gitlab-ai-bot" : botName;
        botEmail = botEmail == null || botEmail.isBlank() ? "gitlab-ai-bot@company.local" : botEmail;
        connectTimeout = connectTimeout == null ? Duration.ofSeconds(5) : connectTimeout;
        readTimeout = readTimeout == null ? Duration.ofSeconds(30) : readTimeout;
        branchPrefix = branchPrefix == null || branchPrefix.isBlank() ? "ai/" : branchPrefix;
        defaultTargetBranch =
                defaultTargetBranch == null || defaultTargetBranch.isBlank() ? "main" : defaultTargetBranch;
        protectedBranches = protectedBranches == null || protectedBranches.isEmpty()
                ? List.of("main", "master", "develop", "release", "production")
                : List.copyOf(protectedBranches);
        mergeRequestLabel =
                mergeRequestLabel == null || mergeRequestLabel.isBlank() ? "AI-GENERATED" : mergeRequestLabel;
        webhook = webhook == null ? new Webhook(null) : webhook;
        pipeline = pipeline == null ? new Pipeline(null, null) : pipeline;
    }

    /** Whether the credentials required to call GitLab have been configured. */
    public boolean isConfigured() {
        return isNotBlank(baseUrl) && isNotBlank(apiToken);
    }

    /** API base, e.g. {@code https://gitlab.company.com/api/v4}. */
    public String apiBaseUrl() {
        if (!isNotBlank(baseUrl)) {
            throw new IllegalStateException("GitLab is not configured: set gitlab.base-url in the settings screen");
        }
        String normalized = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        return normalized.endsWith("/api/v4") ? normalized : normalized + "/api/v4";
    }

    /** Root URL without the API suffix, used to build clone URLs. */
    public String rootUrl() {
        if (!isNotBlank(baseUrl)) {
            throw new IllegalStateException("GitLab is not configured: set gitlab.base-url in the settings screen");
        }
        String normalized = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        return normalized.endsWith("/api/v4") ? normalized.substring(0, normalized.length() - "/api/v4".length()) : normalized;
    }

    /** Shared secret expected in the {@code X-Gitlab-Token} header. */
    public record Webhook(String secret) {}

    /** How long the workflow waits for a CI pipeline before giving up. */
    public record Pipeline(Duration timeout, Duration pollInterval) {
        public Pipeline {
            timeout = timeout == null ? Duration.ofMinutes(60) : timeout;
            pollInterval = pollInterval == null ? Duration.ofSeconds(60) : pollInterval;
        }
    }

    private static boolean isNotBlank(String value) {
        return value != null && !value.isBlank();
    }
}
