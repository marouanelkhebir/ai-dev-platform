package com.company.aidev.agent;

import dev.langchain4j.service.Result;

/**
 * The single LangChain4j AI service used by every agent.
 *
 * <p>The system prompt is supplied per instance through {@code systemMessageProvider}, and the
 * expected JSON shape is described in that prompt. Returning {@link Result} rather than {@code String}
 * gives access to the tool executions and the token usage of the call, which the audit trail needs.
 */
public interface StructuredAssistant {

    Result<String> chat(String userMessage);
}
