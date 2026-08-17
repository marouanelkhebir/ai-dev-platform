package com.mel.aidev.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mel.aidev.agent.AgentType;
import com.mel.aidev.config.WorkflowProperties;
import com.mel.aidev.persistence.entity.AgentExecutionEntity;
import com.mel.aidev.persistence.entity.ProjectEntity;
import com.mel.aidev.persistence.entity.ToolExecutionEntity;
import com.mel.aidev.persistence.entity.WorkflowEntity;
import com.mel.aidev.persistence.entity.WorkflowStepEntity;
import com.mel.aidev.persistence.repository.AgentExecutionRepository;
import com.mel.aidev.persistence.repository.LlmExecutionRepository;
import com.mel.aidev.persistence.repository.ProjectRepository;
import com.mel.aidev.persistence.repository.ToolExecutionRepository;
import com.mel.aidev.persistence.repository.WorkflowRepository;
import com.mel.aidev.persistence.repository.WorkflowStepRepository;
import com.mel.aidev.workflow.WorkflowStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Bounding the lifetime of the detailed audit payloads.
 *
 * <p>Two properties are load-bearing and tested here: the summary is written before anything is
 * deleted, and the purge is idempotent, so an interrupted run loses nothing and a second run over
 * the same workflow does nothing.
 */
class RetentionServiceTest {

    private static final UUID PROJECT_ID = UUID.randomUUID();
    private static final UUID WORKFLOW_ID = UUID.randomUUID();

    private WorkflowRepository workflowRepository;
    private WorkflowStepRepository stepRepository;
    private AgentExecutionRepository agentExecutionRepository;
    private ToolExecutionRepository toolExecutionRepository;
    private RetentionService service;

    @BeforeEach
    void setUp() {
        ProjectRepository projectRepository = mock(ProjectRepository.class);
        workflowRepository = mock(WorkflowRepository.class);
        stepRepository = mock(WorkflowStepRepository.class);
        agentExecutionRepository = mock(AgentExecutionRepository.class);
        toolExecutionRepository = mock(ToolExecutionRepository.class);
        LlmExecutionRepository llmExecutionRepository = mock(LlmExecutionRepository.class);

        service = new RetentionService(
                projectRepository,
                workflowRepository,
                stepRepository,
                agentExecutionRepository,
                toolExecutionRepository,
                llmExecutionRepository,
                new WorkflowProperties(null, null, null, null, null, null, null, null, null, null, null),
                new ObjectMapper());

        when(stepRepository.findByWorkflowIdOrderBySequenceNumberAsc(WORKFLOW_ID)).thenReturn(List.of());
        when(agentExecutionRepository.findByWorkflowIdOrderByStartedAtAsc(WORKFLOW_ID)).thenReturn(List.of());
        when(toolExecutionRepository.findByWorkflowIdOrderByCreatedAtAsc(WORKFLOW_ID)).thenReturn(List.of());
        when(llmExecutionRepository.findByWorkflowIdOrderByCreatedAtAsc(WORKFLOW_ID)).thenReturn(List.of());
        when(workflowRepository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    @DisplayName("summarises a terminated workflow then strips its detailed payloads")
    void shouldSummariseThenPurge() {
        WorkflowEntity workflow = terminatedWorkflow();
        workflow.setTicketAnalysisJson("{\"ticketId\":\"BANK-1245\"}");
        WorkflowStepEntity step = new WorkflowStepEntity(WORKFLOW_ID, 1, WorkflowStatus.DEVELOPING);
        step.complete(WorkflowStatus.RUNNING_LOCAL_TESTS, true, "3 files changed", null);
        AgentExecutionEntity execution = new AgentExecutionEntity(WORKFLOW_ID, AgentType.DEVELOPER, "coder-model", 1);
        execution.setPrompts("system prompt with source code", "user prompt with source code");
        execution.succeeded("raw output", "parsed output");
        ToolExecutionEntity tool = new ToolExecutionEntity(WORKFLOW_ID, UUID.randomUUID(), "runTests", "test", "output", true, 10L);

        when(workflowRepository.findById(WORKFLOW_ID)).thenReturn(Optional.of(workflow));
        when(stepRepository.findByWorkflowIdOrderBySequenceNumberAsc(WORKFLOW_ID)).thenReturn(List.of(step));
        when(agentExecutionRepository.findByWorkflowIdOrderByStartedAtAsc(WORKFLOW_ID)).thenReturn(List.of(execution));
        when(toolExecutionRepository.findByWorkflowIdOrderByCreatedAtAsc(WORKFLOW_ID)).thenReturn(List.of(tool));

        assertThat(service.purgeWorkflow(WORKFLOW_ID)).isTrue();

        assertThat(workflow.getPurgedAt()).isNotNull();
        assertThat(workflow.getTicketAnalysisJson()).isNull();
        assertThat(workflow.getAuditSummary())
                .contains("\"jiraTicket\":\"BANK-1245\"")
                .contains("\"agentCalls\":{\"DEVELOPER\":1}")
                .contains("\"toolCalls\":{\"runTests\":1}");
        assertThat(step.getDetail()).isNull();
        assertThat(execution.getSystemPrompt()).isNull();
        assertThat(execution.getRawOutput()).isNull();
        assertThat(tool.getResult()).isNull();
    }

    @Test
    @DisplayName("does nothing on a workflow that was already purged")
    void shouldBeIdempotent() {
        WorkflowEntity workflow = terminatedWorkflow();
        workflow.purgeDetails("{}");
        when(workflowRepository.findById(WORKFLOW_ID)).thenReturn(Optional.of(workflow));

        assertThat(service.purgeWorkflow(WORKFLOW_ID)).isFalse();
        verify(workflowRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("never touches a workflow that is still running")
    void shouldSkipRunningWorkflow() {
        WorkflowEntity workflow = new WorkflowEntity(WORKFLOW_ID, PROJECT_ID, "BANK-1245", "bank/cm", "main");
        workflow.setStatus(WorkflowStatus.DEVELOPING);
        when(workflowRepository.findById(WORKFLOW_ID)).thenReturn(Optional.of(workflow));

        assertThat(service.purgeWorkflow(WORKFLOW_ID)).isFalse();
        assertThat(workflow.getPurgedAt()).isNull();
    }

    /** 0 means "keep the details forever", for audited or sensitive projects. */
    @Test
    @DisplayName("exempts a project whose retention is zero")
    void shouldExemptProjectWithZeroRetention() {
        ProjectEntity project = new ProjectEntity(PROJECT_ID, "Banque", "bank/cm");
        project.setRetentionDays(0);

        assertThat(service.purgeProject(project)).isZero();
        verify(workflowRepository, never()).findPurgeCandidates(any(), any(), any());
    }

    @Test
    @DisplayName("purges only the candidates the repository hands back, in a bounded batch")
    void shouldPurgeCandidatesOfAProject() {
        ProjectEntity project = new ProjectEntity(PROJECT_ID, "Banque", "bank/cm");
        project.setRetentionDays(30);
        WorkflowEntity workflow = terminatedWorkflow();
        when(workflowRepository.findPurgeCandidates(eq(PROJECT_ID), any(), any())).thenReturn(List.of(WORKFLOW_ID));
        when(workflowRepository.findById(WORKFLOW_ID)).thenReturn(Optional.of(workflow));

        assertThat(service.purgeProject(project)).isEqualTo(1);
        assertThat(workflow.getPurgedAt()).isNotNull();
    }

    private static WorkflowEntity terminatedWorkflow() {
        WorkflowEntity workflow = new WorkflowEntity(WORKFLOW_ID, PROJECT_ID, "BANK-1245", "bank/cm", "main");
        workflow.setBranch("ai/BANK-1245");
        workflow.setStatus(WorkflowStatus.DONE);
        return workflow;
    }
}
