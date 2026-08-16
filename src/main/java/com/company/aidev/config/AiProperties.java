package com.company.aidev.config;

import com.company.aidev.agent.AgentType;
import com.company.aidev.llm.ModelRole;
import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * LLM access configuration.
 *
 * <p>All agents use the OpenAI-compatible API configured by the platform administrator.
 */
@ConfigurationProperties(prefix = "ai")
public record AiProperties(OpenAi openai, Map<ModelRole, String> models, Map<AgentType, AgentSettings> agents) {

    public AiProperties {
        models = models == null ? new EnumMap<>(ModelRole.class) : new EnumMap<>(models);
        agents = agents == null ? new EnumMap<>(AgentType.class) : new EnumMap<>(agents);
    }

    /**
     * Resolves the configured model name used by an agent.
     *
     * @throws IllegalStateException when no model is configured for the resolved role
     */
    public String modelNameFor(AgentType agent) {
        ModelRole role = roleFor(agent);
        String model = models.get(role);
        if (model == null || model.isBlank()) {
            throw new IllegalStateException(
                    "No model configured for role " + role + " (property ai.models." + role.name().toLowerCase() + ")");
        }
        return model;
    }

    public ModelRole roleFor(AgentType agent) {
        AgentSettings settings = agents.get(agent);
        if (settings != null && settings.modelRole() != null) {
            return settings.modelRole();
        }
        return agent.defaultModelRole();
    }

    public AgentSettings settingsFor(AgentType agent) {
        AgentSettings settings = agents.get(agent);
        return settings == null ? AgentSettings.defaults() : settings.withDefaults();
    }

    /** Connection settings of an OpenAI-compatible API. */
    public record OpenAi(
            String baseUrl,
            String apiKey,
            Duration timeout,
            Integer maxRetries,
            Boolean logRequests,
            Boolean logResponses) {

        public OpenAi {
            timeout = timeout == null ? Duration.ofMinutes(3) : timeout;
            maxRetries = maxRetries == null ? 2 : maxRetries;
            logRequests = logRequests != null && logRequests;
            logResponses = logResponses != null && logResponses;
        }
    }

    /** Per-agent sampling settings. */
    public record AgentSettings(ModelRole modelRole, Double temperature, Integer maxTokens, Integer maxToolCalls) {

        public static AgentSettings defaults() {
            return new AgentSettings(null, 0.1, 8192, 60);
        }

        public AgentSettings withDefaults() {
            AgentSettings d = defaults();
            return new AgentSettings(
                    modelRole,
                    temperature == null ? d.temperature() : temperature,
                    maxTokens == null ? d.maxTokens() : maxTokens,
                    maxToolCalls == null ? d.maxToolCalls() : maxToolCalls);
        }
    }
}
