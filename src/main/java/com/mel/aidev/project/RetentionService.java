package com.mel.aidev.project;

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
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Bounds the lifetime of the detailed audit payloads.
 *
 * <p>Prompts, raw model answers and tool results are the bulk of the stored volume and they contain
 * source code of the repository. That is the reason they expire: not disk space, but the fact that a
 * copy of a client's codebase should not sit in a platform database forever.
 *
 * <p>What survives is a compact summary written <em>before</em> anything is deleted, plus everything
 * the schema keeps anyway — the workflow row, its merge requests, and the LLM cost accounting.
 */
@Service
public class RetentionService {

    private static final Logger log = LoggerFactory.getLogger(RetentionService.class);

    private final ProjectRepository projectRepository;
    private final WorkflowRepository workflowRepository;
    private final WorkflowStepRepository stepRepository;
    private final AgentExecutionRepository agentExecutionRepository;
    private final ToolExecutionRepository toolExecutionRepository;
    private final LlmExecutionRepository llmExecutionRepository;
    private final WorkflowProperties workflowProperties;
    private final ObjectMapper objectMapper;

    @SuppressWarnings("java:S107")
    public RetentionService(
            ProjectRepository projectRepository,
            WorkflowRepository workflowRepository,
            WorkflowStepRepository stepRepository,
            AgentExecutionRepository agentExecutionRepository,
            ToolExecutionRepository toolExecutionRepository,
            LlmExecutionRepository llmExecutionRepository,
            WorkflowProperties workflowProperties,
            ObjectMapper objectMapper) {
        this.projectRepository = projectRepository;
        this.workflowRepository = workflowRepository;
        this.stepRepository = stepRepository;
        this.agentExecutionRepository = agentExecutionRepository;
        this.toolExecutionRepository = toolExecutionRepository;
        this.llmExecutionRepository = llmExecutionRepository;
        this.workflowProperties = workflowProperties;
        this.objectMapper = objectMapper;
    }

    /**
     * Runs one round over every project.
     *
     * @return the number of workflows purged
     */
    public int purgeAll() {
        if (!workflowProperties.retention().enabled()) {
            return 0;
        }
        int purged = 0;
        for (ProjectEntity project : projectRepository.findAll()) {
            purged += purgeProject(project);
        }
        if (purged > 0) {
            log.info("Retention purged the details of {} workflow(s)", purged);
        }
        return purged;
    }

    /**
     * Purges one project, in a bounded batch.
     *
     * <p>Bounded so that a first run on a large history does not hold a transaction over the hottest
     * tables of the platform for minutes; what is left is picked up on the next round.
     */
    public int purgeProject(ProjectEntity project) {
        Integer configured = project.getRetentionDays();
        int days = configured == null ? workflowProperties.retention().detailDays() : configured;
        if (days <= 0) {
            return 0; // 0 means "keep the details forever", for audited or sensitive projects
        }
        Instant before = Instant.now().minus(Duration.ofDays(days));
        List<UUID> candidates = workflowRepository.findPurgeCandidates(
                project.getId(), before, PageRequest.of(0, workflowProperties.retention().batchSize()));

        int purged = 0;
        for (UUID workflowId : candidates) {
            purged += purgeWorkflow(workflowId) ? 1 : 0;
        }
        if (purged > 0) {
            log.info("Retention purged {} workflow(s) of project {} finished before {}", purged, project.getName(), before);
        }
        return purged;
    }

    /**
     * Summarises then strips one workflow.
     *
     * <p>Idempotent by construction: {@code purgedAt} is the marker and the eligibility condition, so
     * an interrupted purge resumes on the next round and a purged workflow is never touched twice.
     */
    @Transactional
    public boolean purgeWorkflow(UUID workflowId) {
        WorkflowEntity workflow = workflowRepository.findById(workflowId).orElse(null);
        if (workflow == null || workflow.getPurgedAt() != null || !workflow.getStatus().isTerminal()) {
            return false;
        }

        List<WorkflowStepEntity> steps = stepRepository.findByWorkflowIdOrderBySequenceNumberAsc(workflowId);
        List<AgentExecutionEntity> executions = agentExecutionRepository.findByWorkflowIdOrderByStartedAtAsc(workflowId);
        List<ToolExecutionEntity> tools = toolExecutionRepository.findByWorkflowIdOrderByCreatedAtAsc(workflowId);

        // Written first: an interruption between the summary and the deletion loses nothing, the
        // reverse order would lose the trail entirely.
        workflow.purgeDetails(summarise(workflow, steps, executions, tools));
        workflowRepository.saveAndFlush(workflow);

        steps.forEach(WorkflowStepEntity::purgeDetails);
        stepRepository.saveAll(steps);
        executions.forEach(AgentExecutionEntity::purgePayloads);
        agentExecutionRepository.saveAll(executions);
        tools.forEach(ToolExecutionEntity::purgePayloads);
        toolExecutionRepository.saveAll(tools);

        return true;
    }

    private String summarise(
            WorkflowEntity workflow,
            List<WorkflowStepEntity> steps,
            List<AgentExecutionEntity> executions,
            List<ToolExecutionEntity> tools) {

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("status", workflow.getStatus().name());
        summary.put("jiraTicket", workflow.getJiraTicket());
        summary.put("branch", workflow.getBranch());
        summary.put("baseBranch", workflow.getBaseBranch());
        summary.put("sandboxImage", workflow.getSandboxImage());
        summary.put("mergeRequestUrl", workflow.getMergeRequestUrl());
        summary.put("failureReason", workflow.getFailureReason());
        // Timestamps as ISO-8601 strings: the summary is a long-lived audit artefact and must not
        // depend on how the platform's ObjectMapper happens to be configured when it is written.
        summary.put("createdAt", asText(workflow.getCreatedAt()));
        summary.put("finishedAt", asText(workflow.getFinishedAt()));
        summary.put(
                "attempts",
                Map.of(
                        "development", workflow.getDevelopmentAttempts(),
                        "pipeline", workflow.getPipelineAttempts(),
                        "review", workflow.getReviewAttempts()));

        Map<String, Long> stepDurations = new TreeMap<>();
        for (WorkflowStepEntity step : steps) {
            if (step.getDurationMs() != null) {
                stepDurations.merge(step.getStatusFrom().name(), step.getDurationMs(), Long::sum);
            }
        }
        summary.put("stepDurationsMs", stepDurations);

        Map<String, Integer> callsByAgent = new TreeMap<>();
        for (AgentExecutionEntity execution : executions) {
            callsByAgent.merge(execution.getAgent().name(), 1, Integer::sum);
        }
        summary.put("agentCalls", callsByAgent);

        Map<String, Integer> callsByTool = new TreeMap<>();
        for (ToolExecutionEntity tool : tools) {
            callsByTool.merge(tool.getToolName(), 1, Integer::sum);
        }
        summary.put("toolCalls", callsByTool);

        long totalTokens = 0;
        long costMicros = 0;
        List<String> models = new ArrayList<>();
        for (var llm : llmExecutionRepository.findByWorkflowIdOrderByCreatedAtAsc(workflow.getId())) {
            totalTokens += llm.getTotalTokens() == null ? 0 : llm.getTotalTokens();
            costMicros += llm.getCostMicros() == null ? 0 : llm.getCostMicros();
            if (llm.getModel() != null && !models.contains(llm.getModel())) {
                models.add(llm.getModel());
            }
        }
        summary.put("totalTokens", totalTokens);
        summary.put("costMicros", costMicros);
        summary.put("models", models);

        try {
            return objectMapper.writeValueAsString(summary);
        } catch (JsonProcessingException e) {
            // Losing the summary must not stop the purge, but it must be visible: the detail is
            // about to disappear and nobody would notice the summary never arrived.
            log.error("Unable to serialise the audit summary of workflow {}", workflow.getId(), e);
            return "{\"error\":\"summary could not be serialised\"}";
        }
    }

    private static String asText(Instant instant) {
        return instant == null ? null : instant.toString();
    }
}
