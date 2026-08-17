package com.mel.aidev.observability;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.slf4j.MDC;

/**
 * Structured logging context.
 *
 * <p>Use as a try-with-resources so the MDC is always restored, even on exception:
 *
 * <pre>{@code
 * try (var ignored = LogContext.of().workflow(id).agent("DEVELOPER").apply()) {
 *     ...
 * }
 * }</pre>
 */
public final class LogContext implements AutoCloseable {

    public static final String WORKFLOW_ID = "workflowId";
    public static final String JIRA_TICKET = "jiraTicket";
    public static final String AGENT = "agent";
    public static final String MODEL = "model";
    public static final String GITLAB_PROJECT = "gitlabProject";
    public static final String BRANCH = "branch";
    public static final String MERGE_REQUEST = "mergeRequest";
    public static final String ATTEMPT = "attempt";
    public static final String DURATION_MS = "durationMs";
    public static final String RESULT = "result";

    private final Map<String, String> previous = new LinkedHashMap<>();

    private LogContext(Map<String, String> values) {
        values.forEach((key, value) -> {
            previous.put(key, MDC.get(key));
            if (value == null) {
                MDC.remove(key);
            } else {
                MDC.put(key, value);
            }
        });
    }

    public static Builder of() {
        return new Builder();
    }

    @Override
    public void close() {
        previous.forEach((key, value) -> {
            if (value == null) {
                MDC.remove(key);
            } else {
                MDC.put(key, value);
            }
        });
    }

    /** Fluent builder of MDC entries. */
    public static final class Builder {

        private final Map<String, String> values = new LinkedHashMap<>();

        public Builder workflow(UUID workflowId) {
            return put(WORKFLOW_ID, workflowId == null ? null : workflowId.toString());
        }

        public Builder jiraTicket(String ticket) {
            return put(JIRA_TICKET, ticket);
        }

        public Builder agent(String agent) {
            return put(AGENT, agent);
        }

        public Builder model(String model) {
            return put(MODEL, model);
        }

        public Builder gitlabProject(String project) {
            return put(GITLAB_PROJECT, project);
        }

        public Builder branch(String branch) {
            return put(BRANCH, branch);
        }

        public Builder mergeRequest(Long iid) {
            return put(MERGE_REQUEST, iid == null ? null : iid.toString());
        }

        public Builder attempt(int attempt) {
            return put(ATTEMPT, Integer.toString(attempt));
        }

        public Builder put(String key, String value) {
            values.put(key, value);
            return this;
        }

        public LogContext apply() {
            return new LogContext(values);
        }
    }
}
