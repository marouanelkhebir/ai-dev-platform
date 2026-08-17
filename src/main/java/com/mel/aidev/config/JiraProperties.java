package com.mel.aidev.config;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Jira connection and trigger configuration.
 *
 * <p>Credentials must come from the environment or a secret manager. Never commit a token.
 */
@ConfigurationProperties(prefix = "jira")
public record JiraProperties(
        String baseUrl,
        String email,
        String apiToken,
        String apiVersion,
        Duration connectTimeout,
        Duration readTimeout,
        Trigger trigger,
        Statuses statuses,
        Webhook webhook,
        List<String> acceptanceCriteriaFields) {

    public JiraProperties {
        // "3" for Jira Cloud (ADF payloads), "2" for Jira Server / Data Center (plain text).
        apiVersion = apiVersion == null || apiVersion.isBlank() ? "3" : apiVersion;
        connectTimeout = connectTimeout == null ? Duration.ofSeconds(5) : connectTimeout;
        readTimeout = readTimeout == null ? Duration.ofSeconds(30) : readTimeout;
        trigger = trigger == null ? new Trigger(null, null) : trigger;
        statuses = statuses == null ? new Statuses(null, null, null, null) : statuses;
        webhook = webhook == null ? new Webhook(null) : webhook;
        acceptanceCriteriaFields =
                acceptanceCriteriaFields == null || acceptanceCriteriaFields.isEmpty()
                        ? List.of("customfield_10100")
                        : List.copyOf(acceptanceCriteriaFields);
    }

    /** Whether the credentials required to call Jira have been configured. */
    public boolean isConfigured() {
        return isNotBlank(baseUrl) && isNotBlank(email) && isNotBlank(apiToken);
    }

    /** Base path of the REST API, e.g. {@code https://company.atlassian.net/rest/api/3}. */
    public String apiBaseUrl() {
        if (!isNotBlank(baseUrl)) {
            throw new IllegalStateException("Jira is not configured: set jira.base-url in the settings screen");
        }
        String normalized = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        return normalized + "/rest/api/" + apiVersion;
    }

    /** True when the instance expects Atlassian Document Format payloads. */
    public boolean usesAtlassianDocumentFormat() {
        return "3".equals(apiVersion);
    }

    /** What makes a ticket eligible for the AI team. */
    public record Trigger(String label, String status) {
        public Trigger {
            label = label == null || label.isBlank() ? "agent-ready" : label;
            status = status == null || status.isBlank() ? "READY_FOR_AI" : status;
        }
    }

    /** Jira status names the platform pushes the ticket into. */
    public record Statuses(String inProgress, String needsClarification, String readyForReview, String failed) {
        public Statuses {
            inProgress = blankTo(inProgress, "AI_IN_PROGRESS");
            needsClarification = blankTo(needsClarification, "AI_NEEDS_CLARIFICATION");
            readyForReview = blankTo(readyForReview, "AI_READY_FOR_REVIEW");
            failed = blankTo(failed, "AI_FAILED");
        }

        private static String blankTo(String value, String fallback) {
            return value == null || value.isBlank() ? fallback : value;
        }
    }

    /** Shared secret expected in the {@code X-Webhook-Token} header of incoming Jira webhooks. */
    public record Webhook(String secret) {}

    private static boolean isNotBlank(String value) {
        return value != null && !value.isBlank();
    }
}
