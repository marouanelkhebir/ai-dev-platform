package com.company.aidev.llm;

import com.company.aidev.agent.AgentType;
import dev.langchain4j.model.chat.ChatModel;

/**
 * Single point of access to the LLMs.
 *
 * <p>Agents ask for a model by {@link AgentType}; they never know the base URL, the API key or the
 * physical model name.
 */
public interface LlmModelProvider {

    /** Model configured for this agent (role mapping + per-agent sampling settings). */
    ChatModel modelFor(AgentType agent);

    /** Model configured for a raw role, for utility calls that do not belong to an agent. */
    ChatModel modelFor(ModelRole role);

    /** Configured physical model name, for logging and audit. */
    String modelNameFor(AgentType agent);
}
