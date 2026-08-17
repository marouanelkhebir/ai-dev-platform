package com.mel.aidev.observability;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Routes log output to the step that is currently running for a given workflow.
 *
 * <p>Static on purpose. Two of the three writers cannot receive a Spring bean: {@link
 * StepLogAppender} is instantiated by Logback from {@code logback-spring.xml}, and the Docker
 * callback that streams command output runs on a client thread with nothing but the sandbox in
 * hand. Keying on the workflow id — which both writers have, one from the MDC and one from the
 * sandbox — avoids a {@code ThreadLocal} that neither of those threads would inherit.
 *
 * <p>At most one step runs per workflow at a time: the engine claims a workflow before advancing it
 * and executes its steps sequentially.
 */
public final class StepLogs {

    private static final Map<UUID, StepLogBuffer> ACTIVE = new ConcurrentHashMap<>();
    private static final StepLogBuffer DISABLED = StepLogBuffer.disabled();

    private StepLogs() {}

    /**
     * Starts recording a step, replacing any buffer left behind by a previous one.
     *
     * @param maxChars budget of the step; zero or less disables recording
     */
    public static StepLogBuffer open(UUID workflowId, UUID stepId, int sequenceNumber, int maxChars) {
        if (workflowId == null || maxChars <= 0) {
            return DISABLED;
        }
        StepLogBuffer buffer = new StepLogBuffer(workflowId, stepId, sequenceNumber, maxChars);
        ACTIVE.put(workflowId, buffer);
        return buffer;
    }

    /** The buffer of the running step, or a disabled one when nothing is being recorded. */
    public static StepLogBuffer current(UUID workflowId) {
        if (workflowId == null) {
            return DISABLED;
        }
        return ACTIVE.getOrDefault(workflowId, DISABLED);
    }

    /** Stops recording. Called from a {@code finally}: the buffer must be released even on failure. */
    public static void close(StepLogBuffer buffer) {
        if (buffer != null && buffer.workflowId() != null) {
            ACTIVE.remove(buffer.workflowId(), buffer);
        }
    }

    /** Releases everything. Reserved for tests. */
    static void clear() {
        ACTIVE.clear();
    }
}
