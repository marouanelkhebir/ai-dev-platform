package com.mel.aidev.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Credentials for Bitbucket Cloud. The API token is an Atlassian API token or app password. */
@ConfigurationProperties(prefix = "bitbucket")
public record BitbucketProperties(
        String baseUrl, String username, String apiToken, Duration connectTimeout, Duration readTimeout) {

    public BitbucketProperties {
        baseUrl = baseUrl == null || baseUrl.isBlank() ? "https://api.bitbucket.org" : baseUrl;
        connectTimeout = connectTimeout == null ? Duration.ofSeconds(5) : connectTimeout;
        readTimeout = readTimeout == null ? Duration.ofSeconds(30) : readTimeout;
    }

    public boolean isConfigured() {
        return username != null && !username.isBlank() && apiToken != null && !apiToken.isBlank();
    }

    public String apiBaseUrl() {
        return baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) + "/2.0" : baseUrl + "/2.0";
    }
}
