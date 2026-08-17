package com.mel.aidev.llm;

import com.mel.aidev.agent.AgentType;
import com.mel.aidev.config.AiProperties;
import com.mel.aidev.project.ProjectRuntimeContext;
import com.mel.aidev.settings.PlatformSettings;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * {@link LlmModelProvider} backed by an OpenAI-compatible API.
 *
 * <p>The OpenAI chat model of LangChain4j is configured with the user-provided base URL and API token.
 *
 * <p>Models are immutable and thread safe, so one instance per agent is created lazily and cached.
 */
@Component
public class OpenAiModelProvider implements LlmModelProvider {

    private static final Logger log = LoggerFactory.getLogger(OpenAiModelProvider.class);

    private final PlatformSettings settings;
    private final LlmAuditListener auditListener;
    private final Map<String, ChatModel> cache = new ConcurrentHashMap<>();
    private volatile long cacheVersion = Long.MIN_VALUE;

    public OpenAiModelProvider(PlatformSettings settings, LlmAuditListener auditListener) {
        this.settings = settings;
        this.auditListener = auditListener;
    }

    @Override
    public ChatModel modelFor(AgentType agent) {
        AiProperties properties = currentProperties();
        AiProperties.AgentSettings agentSettings = properties.settingsFor(agent);
        String modelName = modelNameFor(agent);
        String key = agent.name() + '|' + modelName;
        return cache.computeIfAbsent(key, ignored -> build(properties, modelName, agentSettings));
    }

    @Override
    public ChatModel modelFor(ModelRole role) {
        AiProperties properties = currentProperties();
        String pinned = ProjectRuntimeContext.currentModel(role);
        String modelName = pinned == null || pinned.isBlank() ? properties.models().get(role) : pinned;
        if (modelName == null || modelName.isBlank()) {
            throw new IllegalStateException("No model configured for role " + role);
        }
        return cache.computeIfAbsent(
                "ROLE:" + role.name() + '|' + modelName,
                ignored -> build(properties, modelName, AiProperties.AgentSettings.defaults()));
    }

    /**
     * Physical model of an agent: the one pinned by the project of the running workflow, otherwise
     * the platform mapping. The agent itself never has a say, exactly as with its container image.
     */
    @Override
    public String modelNameFor(AgentType agent) {
        AiProperties properties = currentProperties();
        String pinned = ProjectRuntimeContext.currentModel(properties.roleFor(agent));
        return pinned == null || pinned.isBlank() ? properties.modelNameFor(agent) : pinned;
    }

    private AiProperties currentProperties() {
        long currentVersion = settings.version();
        if (cacheVersion != currentVersion) {
            synchronized (cache) {
                if (cacheVersion != currentVersion) {
                    cache.clear();
                    cacheVersion = currentVersion;
                }
            }
        }
        return settings.ai();
    }

    private ChatModel build(AiProperties properties, String modelName, AiProperties.AgentSettings settings) {
        AiProperties.OpenAi openai = properties.openai();
        log.info(
                "Building OpenAI-compatible chat model modelName={} temperature={} maxTokens={} timeout={}",
                modelName,
                settings.temperature(),
                settings.maxTokens(),
                openai.timeout());

        return OpenAiChatModel.builder()
                .baseUrl(openai.baseUrl())
                .apiKey(openai.apiKey())
                .modelName(modelName)
                .temperature(settings.temperature())
                .maxCompletionTokens(settings.maxTokens())
                .timeout(openai.timeout())
                .maxRetries(openai.maxRetries())
                .logRequests(openai.logRequests())
                .logResponses(openai.logResponses())
                .listeners(List.of(auditListener))
                .build();
    }
}
