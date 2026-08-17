package com.mel.aidev.llm;

import com.mel.aidev.observability.PlatformMetrics;
import com.mel.aidev.project.ProjectRuntimeContext;
import dev.langchain4j.model.chat.listener.ChatModelErrorContext;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.listener.ChatModelRequestContext;
import dev.langchain4j.model.chat.listener.ChatModelResponseContext;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.TokenUsage;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Records every OpenAI-compatible API round trip: latency, token usage, finish reason and errors.
 *
 * <p>Prompts are deliberately not logged here — they can contain source code and business data. The
 * agent layer stores a redacted copy in {@code agent_execution} instead.
 */
public class LlmAuditListener implements ChatModelListener {

    private static final Logger log = LoggerFactory.getLogger(LlmAuditListener.class);
    private static final String START_TIME = "aidev.startNanos";

    private final PlatformMetrics metrics;
    private final LlmExecutionRecorder recorder;

    public LlmAuditListener(PlatformMetrics metrics, LlmExecutionRecorder recorder) {
        this.metrics = metrics;
        this.recorder = recorder;
    }

    @Override
    public void onRequest(ChatModelRequestContext context) {
        context.attributes().put(START_TIME, System.nanoTime());
    }

    @Override
    public void onResponse(ChatModelResponseContext context) {
        long durationMs = elapsedMillis(context.attributes().get(START_TIME));
        ChatResponse response = context.chatResponse();
        String model = response.modelName();
        String agent = LlmCallContext.currentAgentName();
        UUID workflowId = LlmCallContext.currentWorkflowId();

        TokenUsage usage = response.tokenUsage();
        Integer input = usage == null ? null : usage.inputTokenCount();
        Integer output = usage == null ? null : usage.outputTokenCount();
        Integer total = usage == null ? null : usage.totalTokenCount();
        String finishReason = response.finishReason() == null ? null : response.finishReason().name();

        metrics.llmTokens(agent, model, input == null ? 0 : input, output == null ? 0 : output);
        recorder.recordSuccess(
                workflowId,
                ProjectRuntimeContext.currentProjectId(),
                agent,
                model,
                durationMs,
                input,
                output,
                total,
                finishReason);

        log.debug(
                "LLM call completed agent={} model={} durationMs={} inputTokens={} outputTokens={} finishReason={}",
                agent,
                model,
                durationMs,
                input,
                output,
                finishReason);
    }

    @Override
    public void onError(ChatModelErrorContext context) {
        long durationMs = elapsedMillis(context.attributes().get(START_TIME));
        String agent = LlmCallContext.currentAgentName();
        UUID workflowId = LlmCallContext.currentWorkflowId();
        String model = context.chatRequest() == null || context.chatRequest().parameters() == null
                ? null
                : context.chatRequest().parameters().modelName();
        String error = context.error() == null ? "unknown" : context.error().toString();

        recorder.recordFailure(workflowId, ProjectRuntimeContext.currentProjectId(), agent, model, durationMs, error);
        log.warn("LLM call failed agent={} model={} durationMs={} error={}", agent, model, durationMs, error);
    }

    private static long elapsedMillis(Object startNanos) {
        if (startNanos instanceof Long start) {
            return (System.nanoTime() - start) / 1_000_000;
        }
        return -1;
    }
}
