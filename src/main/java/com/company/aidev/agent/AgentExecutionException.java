package com.company.aidev.agent;

/** Raised when an agent could not produce a usable answer. */
public class AgentExecutionException extends RuntimeException {

    private final AgentType agent;

    public AgentExecutionException(AgentType agent, String message, Throwable cause) {
        super(message, cause);
        this.agent = agent;
    }

    public AgentType getAgent() {
        return agent;
    }
}
