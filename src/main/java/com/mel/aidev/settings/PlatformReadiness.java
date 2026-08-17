package com.mel.aidev.settings;

import com.mel.aidev.config.AiProperties;
import com.mel.aidev.config.GitLabProperties;
import com.mel.aidev.config.JiraProperties;
import java.util.List;

/** A small, secret-free setup status shown by the administration screen. */
public record PlatformReadiness(boolean ready, List<String> missing) {

    public static PlatformReadiness of(PlatformSettings settings) {
        List<String> missing = new java.util.ArrayList<>();
        JiraProperties jira = settings.jira();
        GitLabProperties gitlab = settings.gitlab();
        AiProperties.OpenAi openai = settings.ai().openai();

        if (!jira.isConfigured()) {
            missing.add("Jira (URL, compte technique et jeton API)");
        }
        if (!gitlab.isConfigured()) {
            missing.add("GitLab (URL et jeton API)");
        }
        if (openai == null || isBlank(openai.baseUrl()) || isBlank(openai.apiKey())) {
            missing.add("API compatible OpenAI (URL et jeton)");
        }
        return new PlatformReadiness(missing.isEmpty(), List.copyOf(missing));
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
