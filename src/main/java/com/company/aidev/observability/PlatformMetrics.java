package com.company.aidev.observability;

import com.company.aidev.agent.AgentType;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import org.springframework.stereotype.Component;

/**
 * Central definition of the platform metrics.
 *
 * <p>Keeping every meter name in one class avoids the usual drift where the same counter is
 * incremented under three different names across the codebase.
 */
@Component
public class PlatformMetrics {

    public static final String WORKFLOW_TOTAL = "ai_workflow_total";
    public static final String WORKFLOW_SUCCESS_TOTAL = "ai_workflow_success_total";
    public static final String WORKFLOW_FAILED_TOTAL = "ai_workflow_failed_total";
    public static final String AGENT_EXECUTION_SECONDS = "ai_agent_execution_seconds";
    public static final String AGENT_CALLS_TOTAL = "ai_agent_calls_total";
    public static final String DEVELOPMENT_ATTEMPTS = "ai_development_attempts";
    public static final String MERGE_REQUEST_CREATED_TOTAL = "ai_merge_request_created_total";
    public static final String PIPELINE_FAILED_TOTAL = "ai_pipeline_failed_total";
    public static final String REVIEW_REJECTED_TOTAL = "ai_review_rejected_total";
    public static final String LLM_TOKENS = "ai_llm_tokens";
    public static final String TOOL_CALLS_TOTAL = "ai_tool_calls_total";
    public static final String SANDBOX_COMMAND_SECONDS = "ai_sandbox_command_seconds";

    private final MeterRegistry registry;

    public PlatformMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void workflowStarted(String project) {
        registry.counter(WORKFLOW_TOTAL, "project", safe(project)).increment();
    }

    public void workflowSucceeded(String project) {
        registry.counter(WORKFLOW_SUCCESS_TOTAL, "project", safe(project)).increment();
    }

    public void workflowFailed(String project, String reason) {
        registry.counter(WORKFLOW_FAILED_TOTAL, "project", safe(project), "reason", safe(reason))
                .increment();
    }

    public void agentExecuted(AgentType agent, String model, Duration duration, boolean success) {
        Timer.builder(AGENT_EXECUTION_SECONDS)
                .tag("agent", agent.name())
                .tag("model", safe(model))
                .tag("success", Boolean.toString(success))
                .publishPercentileHistogram()
                .register(registry)
                .record(duration);
        registry.counter(
                        AGENT_CALLS_TOTAL,
                        "agent",
                        agent.name(),
                        "model",
                        safe(model),
                        "success",
                        Boolean.toString(success))
                .increment();
    }

    public void llmTokens(String agent, String model, int inputTokens, int outputTokens) {
        summary(LLM_TOKENS, agent, model, "input").record(inputTokens);
        summary(LLM_TOKENS, agent, model, "output").record(outputTokens);
    }

    public void developmentAttempt(String project, int attempt) {
        DistributionSummary.builder(DEVELOPMENT_ATTEMPTS)
                .tag("project", safe(project))
                .register(registry)
                .record(attempt);
    }

    public void mergeRequestCreated(String project) {
        registry.counter(MERGE_REQUEST_CREATED_TOTAL, "project", safe(project)).increment();
    }

    public void pipelineFailed(String project) {
        registry.counter(PIPELINE_FAILED_TOTAL, "project", safe(project)).increment();
    }

    public void reviewRejected(String project, String reviewType) {
        registry.counter(REVIEW_REJECTED_TOTAL, "project", safe(project), "type", safe(reviewType))
                .increment();
    }

    public void toolCall(String toolName, boolean success) {
        registry.counter(TOOL_CALLS_TOTAL, "tool", safe(toolName), "success", Boolean.toString(success))
                .increment();
    }

    public void sandboxCommand(String executable, Duration duration, boolean success) {
        Timer.builder(SANDBOX_COMMAND_SECONDS)
                .tag("executable", safe(executable))
                .tag("success", Boolean.toString(success))
                .register(registry)
                .record(duration);
    }

    private DistributionSummary summary(String name, String agent, String model, String direction) {
        return DistributionSummary.builder(name)
                .tag("agent", safe(agent))
                .tag("model", safe(model))
                .tag("direction", direction)
                .register(registry);
    }

    private static String safe(String value) {
        return value == null || value.isBlank() ? "unknown" : value;
    }
}
