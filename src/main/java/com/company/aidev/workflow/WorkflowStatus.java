package com.company.aidev.workflow;

import java.util.EnumSet;
import java.util.Set;

/**
 * States of the development workflow.
 *
 * <p>The engine only ever moves between these states, and every transition is persisted. A restart
 * of the service therefore resumes exactly where it stopped.
 */
public enum WorkflowStatus {

    CREATED,
    ANALYZING_JIRA,
    PLANNING,
    DEVELOPING,
    RUNNING_LOCAL_TESTS,
    PUSHING,
    CREATING_MERGE_REQUEST,
    CODE_REVIEW,
    SECURITY_REVIEW,
    ACCEPTANCE,
    WAITING_HUMAN_APPROVAL,

    /** Terminal: a human must clarify the ticket before the AI can do anything useful. */
    NEEDS_CLARIFICATION,

    /** Terminal: the automatic loops were exhausted. */
    FAILED,

    /** Terminal: cancelled by a human or by the API. */
    CANCELLED,

    /** Terminal: the branch was pushed and its merge request was opened. The merge stays manual. */
    DONE;

    private static final Set<WorkflowStatus> TERMINAL =
            EnumSet.of(DONE, FAILED, CANCELLED, NEEDS_CLARIFICATION);

    /** States where the engine sleeps until an external event (webhook or human) wakes it up. */
    private static final Set<WorkflowStatus> WAITING_EXTERNAL =
            EnumSet.of(WAITING_HUMAN_APPROVAL);

    public boolean isTerminal() {
        return TERMINAL.contains(this);
    }

    public boolean isWaitingExternalEvent() {
        return WAITING_EXTERNAL.contains(this);
    }

    /** True when the engine can pick this workflow up and execute the corresponding step. */
    public boolean isRunnable() {
        return !isTerminal() && !isWaitingExternalEvent();
    }

    /** The statuses a scheduler may pick up, precomputed. */
    public static Set<WorkflowStatus> runnableStatuses() {
        return RUNNABLE;
    }

    private static final Set<WorkflowStatus> RUNNABLE = EnumSet.copyOf(
            EnumSet.allOf(WorkflowStatus.class).stream().filter(WorkflowStatus::isRunnable).toList());
}
