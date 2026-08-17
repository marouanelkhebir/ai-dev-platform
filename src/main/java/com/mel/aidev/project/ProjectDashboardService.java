package com.mel.aidev.project;

import com.mel.aidev.api.dto.ProjectDashboardResponse;
import com.mel.aidev.llm.ModelPricing;
import com.mel.aidev.persistence.entity.WorkflowEntity;
import com.mel.aidev.persistence.repository.LlmExecutionRepository;
import com.mel.aidev.persistence.repository.WorkflowRepository;
import com.mel.aidev.workflow.WorkflowStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Computes the figures of the project dashboard.
 *
 * <p>Cost, duration and success rate all have a definition that must not drift, so they are computed
 * here once and nowhere else. Two of them are worth stating: the success rate excludes cancelled
 * workflows from its denominator, and the duration is measured on {@code finished_at}, which is the
 * only timestamp that does not move after a workflow terminates.
 */
@Service
public class ProjectDashboardService {

    private static final int MAX_FAILURES = 20;

    private final WorkflowRepository workflowRepository;
    private final LlmExecutionRepository llmExecutionRepository;
    private final ModelPricing pricing;

    public ProjectDashboardService(
            WorkflowRepository workflowRepository,
            LlmExecutionRepository llmExecutionRepository,
            ModelPricing pricing) {
        this.workflowRepository = workflowRepository;
        this.llmExecutionRepository = llmExecutionRepository;
        this.pricing = pricing;
    }

    @Transactional(readOnly = true)
    public ProjectDashboardResponse dashboard(UUID projectId, Instant from, Instant to) {
        Instant end = to == null ? Instant.now() : to;
        Instant start = from == null ? end.minus(Duration.ofDays(30)) : from;

        return new ProjectDashboardResponse(
                new ProjectDashboardResponse.Period(start, end),
                workflows(projectId, start, end),
                durations(projectId, start, end),
                llm(projectId, start, end),
                failures(projectId, start, end));
    }

    private ProjectDashboardResponse.Workflows workflows(UUID projectId, Instant from, Instant to) {
        Map<WorkflowStatus, Long> byStatus = new EnumMap<>(WorkflowStatus.class);
        long total = 0;
        for (WorkflowRepository.StatusCount row : workflowRepository.countByStatusForProject(projectId, from, to)) {
            byStatus.put(row.getStatus(), row.getCount());
            total += row.getCount();
        }
        long done = byStatus.getOrDefault(WorkflowStatus.DONE, 0L);
        long failed = byStatus.getOrDefault(WorkflowStatus.FAILED, 0L);
        long settled = done + failed;
        Double successRate = settled == 0 ? null : (double) done / settled;
        return new ProjectDashboardResponse.Workflows(total, byStatus, successRate, failed);
    }

    private ProjectDashboardResponse.Durations durations(UUID projectId, Instant from, Instant to) {
        List<Long> durations = new ArrayList<>();
        for (WorkflowRepository.DurationRow row : workflowRepository.durationsForProject(projectId, from, to)) {
            long millis = Duration.between(row.getStartedAt(), row.getFinishedAt()).toMillis();
            if (millis >= 0) {
                durations.add(millis);
            }
        }
        if (durations.isEmpty()) {
            return new ProjectDashboardResponse.Durations(null, null, null, 0);
        }
        durations.sort(Long::compareTo);
        long sum = durations.stream().mapToLong(Long::longValue).sum();
        return new ProjectDashboardResponse.Durations(
                sum / durations.size(), percentile(durations, 0.50), percentile(durations, 0.95), durations.size());
    }

    /** Nearest-rank percentile on a sorted list; exact and obvious, which matters more here than smoothing. */
    private static long percentile(List<Long> sorted, double fraction) {
        int rank = (int) Math.ceil(fraction * sorted.size());
        return sorted.get(Math.max(0, Math.min(sorted.size() - 1, rank - 1)));
    }

    private ProjectDashboardResponse.Llm llm(UUID projectId, Instant from, Instant to) {
        LlmExecutionRepository.TokenTotals totals = llmExecutionRepository.totalsFor(projectId, from, to);
        return new ProjectDashboardResponse.Llm(
                totals == null ? 0 : totals.getPromptTokens(),
                totals == null ? 0 : totals.getCompletionTokens(),
                totals == null ? 0 : totals.getTotalTokens(),
                totals == null ? 0 : totals.getCostMicros(),
                pricing.currency(),
                totals == null ? 0 : totals.getCalls(),
                llmExecutionRepository.unpricedModels(projectId, from, to),
                map(llmExecutionRepository.breakdownByModel(projectId, from, to)),
                map(llmExecutionRepository.breakdownByAgent(projectId, from, to)));
    }

    private static List<ProjectDashboardResponse.Breakdown> map(List<LlmExecutionRepository.Breakdown> rows) {
        return rows.stream()
                .map(row -> new ProjectDashboardResponse.Breakdown(
                        row.getLabel() == null ? "unknown" : row.getLabel(),
                        row.getTotalTokens(),
                        row.getCostMicros(),
                        row.getCalls()))
                .toList();
    }

    private List<ProjectDashboardResponse.FailureView> failures(UUID projectId, Instant from, Instant to) {
        List<WorkflowEntity> failures =
                workflowRepository.recentFailures(projectId, from, to, PageRequest.of(0, MAX_FAILURES));
        return failures.stream()
                .map(workflow -> new ProjectDashboardResponse.FailureView(
                        workflow.getId(),
                        workflow.getJiraTicket(),
                        workflow.getStatus(),
                        workflow.getFailureReason(),
                        workflow.getMergeRequestUrl(),
                        workflow.getFinishedAt()))
                .toList();
    }
}
