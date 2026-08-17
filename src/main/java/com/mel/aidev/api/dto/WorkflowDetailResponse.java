package com.mel.aidev.api.dto;

import com.mel.aidev.domain.AcceptanceReport;
import com.mel.aidev.domain.CodeReview;
import com.mel.aidev.domain.SecurityReport;
import com.mel.aidev.domain.TechnicalPlan;
import com.mel.aidev.domain.TestReport;
import com.mel.aidev.domain.TicketAnalysis;
import com.mel.aidev.persistence.entity.WorkflowStepEntity;
import com.mel.aidev.workflow.WorkflowStatus;
import java.time.Instant;
import java.util.List;

/**
 * Full audit view of a workflow: what each agent produced, in order.
 *
 * <p>This is what makes a ticket auditable end to end — Jira, agents, prompts, tools, tests, merge
 * request, reviews and the human decision.
 */
public record WorkflowDetailResponse(
        WorkflowResponse workflow,
        TicketAnalysis ticketAnalysis,
        TechnicalPlan technicalPlan,
        TestReport testReport,
        CodeReview codeReview,
        SecurityReport securityReport,
        AcceptanceReport acceptanceReport,
        List<StepView> steps,
        List<AgentExecutionView> agentExecutions,
        List<StepLogView> stepLogs) {

    /**
     * One transition of the state machine.
     *
     * <p>A step with no {@code to} is still running: the console times it from {@code startedAt}.
     */
    public record StepView(
            int sequence,
            WorkflowStatus from,
            WorkflowStatus to,
            Boolean successful,
            Long durationMs,
            String detail,
            String error,
            Instant startedAt) {

        public static StepView from(WorkflowStepEntity step) {
            return new StepView(
                    step.getSequenceNumber(),
                    step.getStatusFrom(),
                    step.getStatusTo(),
                    step.getSuccessful(),
                    step.getDurationMs(),
                    step.getDetail(),
                    step.getError(),
                    step.getStartedAt());
        }
    }

    /** One agent invocation, without the prompts (which can be large and are fetched separately). */
    public record AgentExecutionView(
            String agent,
            String model,
            int attempt,
            Boolean successful,
            Long durationMs,
            Instant startedAt,
            String error,
            String response,
            String structuredResponse,
            List<ToolExecutionView> tools) {}

    /**
     * What the full log of a step weighs, without inflating it.
     *
     * <p>The log itself is fetched on demand from {@code /api/workflows/{id}/steps/{sequence}/logs}:
     * it is megabytes of build output and has no place in the detail payload.
     *
     * @param truncated true when the step exceeded its budget and its middle was dropped
     */
    public record StepLogView(int sequence, long characters, long compressedBytes, boolean truncated) {}

    /** A redacted tool invocation, including the command output produced in the sandbox. */
    public record ToolExecutionView(
            String name,
            String arguments,
            String output,
            boolean successful,
            Long durationMs,
            Instant createdAt) {}
}
