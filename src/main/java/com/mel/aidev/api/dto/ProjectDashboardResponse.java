package com.mel.aidev.api.dto;

import com.mel.aidev.workflow.WorkflowStatus;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Metrics of one project over a period.
 *
 * <p>The definitions are fixed here and in the service, not left to each screen: two views computing
 * "success rate" their own way is how a dashboard stops being trusted.
 *
 * @param period window the figures cover, {@code from} inclusive and {@code to} exclusive
 * @param workflows counts and success rate
 * @param duration wall-clock time from creation to termination
 * @param llm token and cost accounting
 * @param failures the most recent failed workflows, with their reason
 */
public record ProjectDashboardResponse(
        Period period, Workflows workflows, Durations duration, Llm llm, List<FailureView> failures) {

    public record Period(Instant from, Instant to) {}

    /**
     * @param successRate {@code DONE / (DONE + FAILED)}. Cancelled and clarification-blocked
     *     workflows are excluded from the denominator: they are human decisions, not failures of the
     *     platform. Null when nothing terminated in the period.
     */
    public record Workflows(
            long total, Map<WorkflowStatus, Long> byStatus, Double successRate, long inError) {}

    /** Wall-clock durations, computed on terminated workflows only. */
    public record Durations(Long averageMs, Long p50Ms, Long p95Ms, long sampleSize) {}

    /**
     * @param unpricedModels models whose calls were recorded without a tariff. Reported apart rather
     *     than folded into the total, which would understate it silently.
     */
    public record Llm(
            long promptTokens,
            long completionTokens,
            long totalTokens,
            long costMicros,
            String currency,
            long calls,
            List<String> unpricedModels,
            List<Breakdown> byModel,
            List<Breakdown> byAgent) {}

    public record Breakdown(String label, long totalTokens, long costMicros, long calls) {}

    public record FailureView(
            UUID workflowId,
            String jiraTicket,
            WorkflowStatus status,
            String failureReason,
            String mergeRequestUrl,
            Instant finishedAt) {}
}
