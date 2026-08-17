package com.mel.aidev.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.mel.aidev.api.dto.ProjectDashboardResponse;
import com.mel.aidev.llm.ModelPricing;
import com.mel.aidev.persistence.repository.LlmExecutionRepository;
import com.mel.aidev.persistence.repository.WorkflowRepository;
import com.mel.aidev.workflow.WorkflowStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The definitions behind the dashboard figures.
 *
 * <p>These are pinned by tests because a success rate computed two different ways in two screens is
 * how a dashboard stops being believed.
 */
class ProjectDashboardServiceTest {

    private static final UUID PROJECT_ID = UUID.randomUUID();
    private static final Instant FROM = Instant.parse("2026-07-01T00:00:00Z");
    private static final Instant TO = Instant.parse("2026-08-01T00:00:00Z");

    private WorkflowRepository workflowRepository;
    private LlmExecutionRepository llmExecutionRepository;
    private ProjectDashboardService service;

    @BeforeEach
    void setUp() {
        workflowRepository = mock(WorkflowRepository.class);
        llmExecutionRepository = mock(LlmExecutionRepository.class);
        ModelPricing pricing = mock(ModelPricing.class);
        when(pricing.currency()).thenReturn("USD");

        service = new ProjectDashboardService(workflowRepository, llmExecutionRepository, pricing);

        when(workflowRepository.countByStatusForProject(eq(PROJECT_ID), any(), any())).thenReturn(List.of());
        when(workflowRepository.durationsForProject(eq(PROJECT_ID), any(), any())).thenReturn(List.of());
        when(workflowRepository.recentFailures(eq(PROJECT_ID), any(), any(), any())).thenReturn(List.of());
        when(llmExecutionRepository.totalsFor(eq(PROJECT_ID), any(), any())).thenReturn(null);
        when(llmExecutionRepository.unpricedModels(eq(PROJECT_ID), any(), any())).thenReturn(List.of());
        when(llmExecutionRepository.breakdownByModel(eq(PROJECT_ID), any(), any())).thenReturn(List.of());
        when(llmExecutionRepository.breakdownByAgent(eq(PROJECT_ID), any(), any())).thenReturn(List.of());
    }

    /**
     * Cancelled and clarification-blocked workflows are human decisions, not failures of the
     * platform, so they stay out of the denominator.
     */
    @Test
    @DisplayName("computes the success rate on DONE and FAILED only")
    void shouldExcludeCancelledFromSuccessRate() {
        when(workflowRepository.countByStatusForProject(eq(PROJECT_ID), any(), any()))
                .thenReturn(List.of(
                        statusCount(WorkflowStatus.DONE, 8),
                        statusCount(WorkflowStatus.FAILED, 2),
                        statusCount(WorkflowStatus.CANCELLED, 10),
                        statusCount(WorkflowStatus.NEEDS_CLARIFICATION, 5)));

        ProjectDashboardResponse.Workflows workflows = service.dashboard(PROJECT_ID, FROM, TO).workflows();

        assertThat(workflows.total()).isEqualTo(25);
        assertThat(workflows.successRate()).isEqualTo(0.8);
        assertThat(workflows.inError()).isEqualTo(2);
    }

    @Test
    @DisplayName("reports no success rate when nothing settled in the period")
    void shouldReportNoRateWithoutSettledWorkflow() {
        when(workflowRepository.countByStatusForProject(eq(PROJECT_ID), any(), any()))
                .thenReturn(List.of(statusCount(WorkflowStatus.DEVELOPING, 3)));

        assertThat(service.dashboard(PROJECT_ID, FROM, TO).workflows().successRate()).isNull();
    }

    @Test
    @DisplayName("measures durations between creation and termination")
    void shouldComputeDurations() {
        when(workflowRepository.durationsForProject(eq(PROJECT_ID), any(), any()))
                .thenReturn(List.of(
                        duration(Duration.ofMinutes(10)),
                        duration(Duration.ofMinutes(20)),
                        duration(Duration.ofMinutes(30)),
                        duration(Duration.ofMinutes(120))));

        ProjectDashboardResponse.Durations durations = service.dashboard(PROJECT_ID, FROM, TO).duration();

        assertThat(durations.sampleSize()).isEqualTo(4);
        assertThat(durations.averageMs()).isEqualTo(Duration.ofMinutes(45).toMillis());
        assertThat(durations.p50Ms()).isEqualTo(Duration.ofMinutes(20).toMillis());
        assertThat(durations.p95Ms()).isEqualTo(Duration.ofMinutes(120).toMillis());
    }

    @Test
    @DisplayName("reports the unpriced models apart from the cost")
    void shouldReportUnpricedModels() {
        when(llmExecutionRepository.totalsFor(eq(PROJECT_ID), any(), any())).thenReturn(totals(1000, 200, 1200, 42000, 7));
        when(llmExecutionRepository.unpricedModels(eq(PROJECT_ID), any(), any())).thenReturn(List.of("local-model"));

        ProjectDashboardResponse.Llm llm = service.dashboard(PROJECT_ID, FROM, TO).llm();

        assertThat(llm.totalTokens()).isEqualTo(1200);
        assertThat(llm.costMicros()).isEqualTo(42000);
        assertThat(llm.currency()).isEqualTo("USD");
        assertThat(llm.unpricedModels()).containsExactly("local-model");
    }

    @Test
    @DisplayName("defaults to the last thirty days when no period is given")
    void shouldDefaultToLastThirtyDays() {
        ProjectDashboardResponse.Period period = service.dashboard(PROJECT_ID, null, null).period();

        assertThat(Duration.between(period.from(), period.to()).toDays()).isEqualTo(30);
    }

    private static WorkflowRepository.StatusCount statusCount(WorkflowStatus status, long count) {
        return new WorkflowRepository.StatusCount() {
            @Override
            public WorkflowStatus getStatus() {
                return status;
            }

            @Override
            public long getCount() {
                return count;
            }
        };
    }

    private static WorkflowRepository.DurationRow duration(Duration length) {
        return new WorkflowRepository.DurationRow() {
            @Override
            public Instant getStartedAt() {
                return FROM;
            }

            @Override
            public Instant getFinishedAt() {
                return FROM.plus(length);
            }
        };
    }

    private static LlmExecutionRepository.TokenTotals totals(
            long prompt, long completion, long total, long cost, long calls) {
        return new LlmExecutionRepository.TokenTotals() {
            @Override
            public long getPromptTokens() {
                return prompt;
            }

            @Override
            public long getCompletionTokens() {
                return completion;
            }

            @Override
            public long getTotalTokens() {
                return total;
            }

            @Override
            public long getCostMicros() {
                return cost;
            }

            @Override
            public long getCalls() {
                return calls;
            }
        };
    }
}
