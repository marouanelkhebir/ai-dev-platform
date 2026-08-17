package com.mel.aidev.agent;

import java.util.List;
import java.util.UUID;

/**
 * One agent invocation.
 *
 * @param agent which agent is running
 * @param workflowId owning workflow, used for audit and for LLM attribution
 * @param attempt 1-based attempt number
 * @param systemPrompt full system prompt, already rendered
 * @param userPrompt full user message, already rendered
 * @param tools tool objects exposed to this agent, possibly empty
 */
public record AgentRequest(
        AgentType agent, UUID workflowId, int attempt, String systemPrompt, String userPrompt, List<Object> tools) {

    public AgentRequest {
        tools = tools == null ? List.of() : List.copyOf(tools);
    }

    public static AgentRequest withoutTools(
            AgentType agent, UUID workflowId, int attempt, String systemPrompt, String userPrompt) {
        return new AgentRequest(agent, workflowId, attempt, systemPrompt, userPrompt, List.of());
    }
}
