package com.mel.aidev.llm;

import com.mel.aidev.agent.AgentType;
import java.util.UUID;

/**
 * Thread-local attribution of an LLM call.
 *
 * <p>LangChain4j listeners do not receive business context, so the agent layer publishes it here
 * before invoking the model. Every call site uses try-with-resources, which guarantees the scope is
 * closed even when the model throws.
 */
public final class LlmCallContext implements AutoCloseable {

    private static final ThreadLocal<LlmCallContext> CURRENT = new ThreadLocal<>();

    private final UUID workflowId;
    private final AgentType agent;
    private final LlmCallContext previous;

    private LlmCallContext(UUID workflowId, AgentType agent, LlmCallContext previous) {
        this.workflowId = workflowId;
        this.agent = agent;
        this.previous = previous;
    }

    public static LlmCallContext open(UUID workflowId, AgentType agent) {
        LlmCallContext context = new LlmCallContext(workflowId, agent, CURRENT.get());
        CURRENT.set(context);
        return context;
    }

    public static UUID currentWorkflowId() {
        LlmCallContext current = CURRENT.get();
        return current == null ? null : current.workflowId;
    }

    public static AgentType currentAgent() {
        LlmCallContext current = CURRENT.get();
        return current == null ? null : current.agent;
    }

    public static String currentAgentName() {
        AgentType agent = currentAgent();
        return agent == null ? "unknown" : agent.name();
    }

    @Override
    public void close() {
        if (previous == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(previous);
        }
    }
}
