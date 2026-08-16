package com.company.aidev.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings of the platform itself.
 *
 * <p>No validation here on purpose: the platform must start with an empty API key so that the
 * settings screen can be reached and the key set. When none is configured, {@code SettingsService}
 * generates one at first start and logs it once.
 *
 * @param apiKey static key expected in the {@code X-Api-Key} header of every {@code /api/**} call
 */
@ConfigurationProperties(prefix = "platform")
public record PlatformProperties(String apiKey) {

    public boolean hasApiKey() {
        return apiKey != null && !apiKey.isBlank();
    }
}
