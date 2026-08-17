package com.mel.aidev.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Credentials for GitHub.
 *
 * <p>The token is a personal access token (classic or fine-grained) or a GitHub App installation
 * token. For GitHub Enterprise Server the base URL is the API root, {@code https://ghe.example.com/api/v3}.
 */
@ConfigurationProperties(prefix = "github")
public record GitHubProperties(String baseUrl, String apiToken, Duration connectTimeout, Duration readTimeout) {

    public GitHubProperties {
        baseUrl = baseUrl == null || baseUrl.isBlank() ? "https://api.github.com" : baseUrl;
        connectTimeout = connectTimeout == null ? Duration.ofSeconds(5) : connectTimeout;
        readTimeout = readTimeout == null ? Duration.ofSeconds(30) : readTimeout;
    }

    public boolean isConfigured() {
        return apiToken != null && !apiToken.isBlank();
    }

    public String apiBaseUrl() {
        return baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }
}
