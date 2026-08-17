package com.mel.aidev.observability;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.AppenderBase;
import java.time.Instant;
import java.util.UUID;

/**
 * Copies the platform's own log events into the log of the step that produced them.
 *
 * <p>Container output alone does not explain every failure: a workflow that stops on a GitLab 403 or
 * on an unparsable model answer never reaches a build. Those failures are only visible in the
 * platform log, which is why the step trail carries both.
 *
 * <p>Routing is by the {@code workflowId} MDC entry, set for the whole run by {@link LogContext}.
 * Events without it — startup, schedulers, HTTP traffic of other tickets — are ignored.
 */
public class StepLogAppender extends AppenderBase<ILoggingEvent> {

    @Override
    protected void append(ILoggingEvent event) {
        try {
            String workflowId = event.getMDCPropertyMap().get(LogContext.WORKFLOW_ID);
            if (workflowId == null || workflowId.isBlank()) {
                return;
            }
            StepLogBuffer buffer = StepLogs.current(parse(workflowId));
            if (!buffer.enabled()) {
                return;
            }
            StringBuilder line = new StringBuilder(160)
                    .append(Instant.ofEpochMilli(event.getTimeStamp()))
                    .append(' ')
                    .append(event.getLevel())
                    .append(' ')
                    .append(simpleName(event.getLoggerName()))
                    .append(" - ")
                    .append(event.getFormattedMessage());
            if (event.getThrowableProxy() != null) {
                line.append('\n').append(ThrowableProxyUtil.asString(event.getThrowableProxy()));
            }
            buffer.line(line.toString());
        } catch (RuntimeException e) {
            // An appender must never break the call that logged: losing a line costs a detail of the
            // trail, throwing here would surface as a failure of whatever was being logged.
            addError("Unable to capture a log event into the step log", e);
        }
    }

    private static UUID parse(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String simpleName(String loggerName) {
        if (loggerName == null) {
            return "?";
        }
        int lastDot = loggerName.lastIndexOf('.');
        return lastDot < 0 ? loggerName : loggerName.substring(lastDot + 1);
    }
}
