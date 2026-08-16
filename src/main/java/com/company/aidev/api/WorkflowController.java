package com.company.aidev.api;

import com.company.aidev.api.dto.CreateWorkflowRequest;
import com.company.aidev.api.dto.CreateMessageWorkflowRequest;
import com.company.aidev.api.dto.ClarifyWorkflowRequest;
import com.company.aidev.api.dto.WorkflowDetailResponse;
import com.company.aidev.api.dto.WorkflowResponse;
import com.company.aidev.domain.AcceptanceReport;
import com.company.aidev.domain.CodeReview;
import com.company.aidev.domain.SecurityReport;
import com.company.aidev.domain.TechnicalPlan;
import com.company.aidev.domain.TestReport;
import com.company.aidev.domain.TicketAnalysis;
import com.company.aidev.persistence.entity.WorkflowEntity;
import com.company.aidev.persistence.repository.AgentExecutionRepository;
import com.company.aidev.persistence.repository.ToolExecutionRepository;
import com.company.aidev.persistence.repository.WorkflowStepRepository;
import com.company.aidev.workflow.DevelopmentWorkflowService;
import com.company.aidev.workflow.WorkflowArtifactCodec;
import com.company.aidev.workflow.WorkflowStatus;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** REST API of the platform. */
@RestController
@RequestMapping("/api/workflows")
public class WorkflowController {

    private final DevelopmentWorkflowService workflowService;
    private final WorkflowStepRepository stepRepository;
    private final AgentExecutionRepository agentExecutionRepository;
    private final ToolExecutionRepository toolExecutionRepository;
    private final WorkflowArtifactCodec codec;

    public WorkflowController(
            DevelopmentWorkflowService workflowService,
            WorkflowStepRepository stepRepository,
            AgentExecutionRepository agentExecutionRepository,
            ToolExecutionRepository toolExecutionRepository,
            WorkflowArtifactCodec codec) {
        this.workflowService = workflowService;
        this.stepRepository = stepRepository;
        this.agentExecutionRepository = agentExecutionRepository;
        this.toolExecutionRepository = toolExecutionRepository;
        this.codec = codec;
    }

    @PostMapping
    public ResponseEntity<WorkflowResponse> create(@Valid @RequestBody CreateWorkflowRequest request) {
        WorkflowEntity workflow = workflowService.createOrGetActive(request.jiraTicket(), request.gitlabProjectId());
        workflowService.startAsync(workflow.getId());
        return ResponseEntity.created(URI.create("/api/workflows/" + workflow.getId()))
                .body(WorkflowResponse.from(workflow));
    }

    /** Starts a workflow from a free-form development request, without requiring Jira. */
    @PostMapping("/message")
    public ResponseEntity<WorkflowResponse> createFromMessage(@Valid @RequestBody CreateMessageWorkflowRequest request) {
        WorkflowEntity workflow = workflowService.createFromMessage(request.message(), request.gitlabProjectId());
        workflowService.startAsync(workflow.getId());
        return ResponseEntity.created(URI.create("/api/workflows/" + workflow.getId()))
                .body(WorkflowResponse.from(workflow));
    }

    @GetMapping
    public Page<WorkflowResponse> list(
            @RequestParam(required = false) WorkflowStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return workflowService
                .list(status, PageRequest.of(page, Math.min(size, 100)))
                .map(WorkflowResponse::from);
    }

    @GetMapping("/{id}")
    public WorkflowDetailResponse get(@PathVariable UUID id) {
        WorkflowEntity workflow = workflowService.get(id);

        List<WorkflowDetailResponse.StepView> steps =
                stepRepository.findByWorkflowIdOrderBySequenceNumberAsc(id).stream()
                        .map(step -> new WorkflowDetailResponse.StepView(
                                step.getSequenceNumber(),
                                step.getStatusFrom(),
                                step.getStatusTo(),
                                step.getSuccessful(),
                                step.getDurationMs(),
                                step.getDetail(),
                                step.getError(),
                                step.getStartedAt()))
                        .toList();

        Map<UUID, List<WorkflowDetailResponse.ToolExecutionView>> toolsByExecution =
                toolExecutionRepository.findByWorkflowIdOrderByCreatedAtAsc(id).stream()
                        .filter(tool -> tool.getAgentExecutionId() != null)
                        .collect(Collectors.groupingBy(
                                tool -> tool.getAgentExecutionId(),
                                Collectors.mapping(
                                        tool -> new WorkflowDetailResponse.ToolExecutionView(
                                                tool.getToolName(),
                                                tool.getArguments(),
                                                tool.getResult(),
                                                tool.isSuccessful(),
                                                tool.getDurationMs(),
                                                tool.getCreatedAt()),
                                        Collectors.toList())));

        List<WorkflowDetailResponse.AgentExecutionView> executions =
                agentExecutionRepository.findByWorkflowIdOrderByStartedAtAsc(id).stream()
                        .map(execution -> new WorkflowDetailResponse.AgentExecutionView(
                                execution.getAgent().name(),
                                execution.getModel(),
                                execution.getAttempt(),
                                execution.getSuccessful(),
                                execution.getDurationMs(),
                                execution.getStartedAt(),
                                execution.getError(),
                                execution.getRawOutput(),
                                execution.getParsedOutput(),
                                toolsByExecution.getOrDefault(execution.getId(), List.of())))
                        .toList();

        return new WorkflowDetailResponse(
                WorkflowResponse.from(workflow),
                codec.read(workflow.getTicketAnalysisJson(), TicketAnalysis.class).orElse(null),
                codec.read(workflow.getTechnicalPlanJson(), TechnicalPlan.class).orElse(null),
                codec.read(workflow.getTestReportJson(), TestReport.class).orElse(null),
                codec.read(workflow.getCodeReviewJson(), CodeReview.class).orElse(null),
                codec.read(workflow.getSecurityReportJson(), SecurityReport.class).orElse(null),
                codec.read(workflow.getAcceptanceReportJson(), AcceptanceReport.class).orElse(null),
                steps,
                executions);
    }

    @PostMapping("/{id}/retry")
    public WorkflowResponse retry(@PathVariable UUID id) {
        WorkflowEntity workflow = workflowService.retry(id);
        workflowService.startAsync(id);
        return WorkflowResponse.from(workflow);
    }

    /** Stores the missing context and restarts analysis of a clarification-blocked workflow. */
    @PostMapping("/{id}/clarification")
    public WorkflowResponse clarify(@PathVariable UUID id, @Valid @RequestBody ClarifyWorkflowRequest request) {
        WorkflowEntity workflow = workflowService.clarify(id, request.clarification());
        workflowService.startAsync(id);
        return WorkflowResponse.from(workflow);
    }

    @PostMapping("/{id}/cancel")
    public WorkflowResponse cancel(@PathVariable UUID id) {
        return WorkflowResponse.from(workflowService.cancel(id));
    }

    /**
     * Records the human decision.
     *
     * <p>This does not merge anything: automatic merge is not implemented in this version, and the
     * merge stays a manual action in GitLab.
     */
    @PostMapping("/{id}/approve")
    public WorkflowResponse approve(@PathVariable UUID id, @RequestBody(required = false) Map<String, String> body) {
        String approver = body == null ? null : body.get("approver");
        return WorkflowResponse.from(workflowService.approve(id, approver == null ? "unknown" : approver));
    }
}
