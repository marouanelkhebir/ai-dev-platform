package com.company.aidev.api.dto;

import com.company.aidev.domain.AcceptanceReport;
import com.company.aidev.domain.CodeReview;
import com.company.aidev.domain.SecurityReport;
import com.company.aidev.domain.TechnicalPlan;
import com.company.aidev.domain.TestReport;
import com.company.aidev.domain.TicketAnalysis;
import com.company.aidev.workflow.WorkflowStatus;
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
        List<AgentExecutionView> agentExecutions) {

    /** One transition of the state machine. */
    public record StepView(
            int sequence,
            WorkflowStatus from,
            WorkflowStatus to,
            Boolean successful,
            Long durationMs,
            String detail,
            String error,
            Instant startedAt) {}

    /** One agent invocation, without the prompts (which can be large and are fetched separately). */
    public record AgentExecutionView(
            String agent,
            String model,
            int attempt,
            Boolean successful,
            Long durationMs,
            Instant startedAt,
            String error) {}
}
