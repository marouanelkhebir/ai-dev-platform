package com.mel.aidev.tool;

import com.mel.aidev.observability.PlatformMetrics;
import com.mel.aidev.persistence.entity.ToolExecutionEntity;
import com.mel.aidev.persistence.repository.ToolExecutionRepository;
import com.mel.aidev.security.SecretRedactor;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Wraps every tool call with auditing, metrics and redaction.
 *
 * <p>A tool failure is returned to the model as text rather than thrown: an agent that receives
 * "file not found" can correct itself, whereas an exception aborts the whole attempt.
 */
@Component
public class ToolExecutionRecorder {

    private static final Logger log = LoggerFactory.getLogger(ToolExecutionRecorder.class);
    private static final int MAX_AUDIT_CHARS = 20_000;

    private final ToolExecutionRepository repository;
    private final SecretRedactor redactor;
    private final PlatformMetrics metrics;

    public ToolExecutionRecorder(
            ToolExecutionRepository repository, SecretRedactor redactor, PlatformMetrics metrics) {
        this.repository = repository;
        this.redactor = redactor;
        this.metrics = metrics;
    }

    /**
     * Runs a tool, records it, and converts any failure into a message the model can act on.
     *
     * @return the tool output, or a {@code ERROR: ...} message
     */
    public String record(
            UUID workflowId, UUID agentExecutionId, String toolName, String arguments, Supplier<String> action) {
        Instant start = Instant.now();
        String result;
        boolean success;
        try {
            result = action.get();
            success = true;
        } catch (RuntimeException e) {
            result = "ERROR: " + e.getMessage();
            success = false;
            log.warn("Tool {} failed for workflow {}: {}", toolName, workflowId, e.toString());
        }
        long durationMs = Duration.between(start, Instant.now()).toMillis();
        metrics.toolCall(toolName, success);
        persist(workflowId, agentExecutionId, toolName, arguments, result, success, durationMs);
        return result;
    }

    // Not annotated: this is called from within the class, where a @Transactional proxy would not
    // apply anyway. SimpleJpaRepository#save is itself transactional, so the row is committed
    // immediately when no outer transaction is running, which is the case for engine steps.
    private void persist(
            UUID workflowId,
            UUID agentExecutionId,
            String toolName,
            String arguments,
            String result,
            boolean success,
            long durationMs) {
        try {
            repository.save(new ToolExecutionEntity(
                    workflowId,
                    agentExecutionId,
                    toolName,
                    redactor.redactAndTruncate(arguments, MAX_AUDIT_CHARS),
                    redactor.redactAndTruncate(result, MAX_AUDIT_CHARS),
                    success,
                    durationMs));
        } catch (RuntimeException e) {
            log.warn("Unable to persist tool execution {}: {}", toolName, e.toString());
        }
    }
}
