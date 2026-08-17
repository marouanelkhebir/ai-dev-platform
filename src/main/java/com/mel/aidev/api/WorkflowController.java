package com.mel.aidev.api;

import com.mel.aidev.api.dto.CreateWorkflowRequest;
import com.mel.aidev.api.dto.CreateMessageWorkflowRequest;
import com.mel.aidev.api.dto.ClarifyWorkflowRequest;
import com.mel.aidev.api.dto.WorkflowDetailResponse;
import com.mel.aidev.api.dto.WorkflowResponse;
import com.mel.aidev.domain.AcceptanceReport;
import com.mel.aidev.domain.CodeReview;
import com.mel.aidev.domain.SecurityReport;
import com.mel.aidev.domain.TechnicalPlan;
import com.mel.aidev.domain.TestReport;
import com.mel.aidev.domain.TicketAnalysis;
import com.mel.aidev.persistence.entity.ProjectEntity;
import com.mel.aidev.persistence.entity.WorkflowEntity;
import com.mel.aidev.persistence.repository.AgentExecutionRepository;
import com.mel.aidev.persistence.repository.ToolExecutionRepository;
import com.mel.aidev.persistence.repository.WorkflowStepRepository;
import com.mel.aidev.project.ProjectService;
import com.mel.aidev.workflow.DevelopmentWorkflowService;
import com.mel.aidev.workflow.WorkflowArtifactCodec;
import com.mel.aidev.workflow.WorkflowStatus;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** REST API of the platform. */
@RestController
@RequestMapping("/api/workflows")
public class WorkflowController {

    private final DevelopmentWorkflowService workflowService;
    private final ProjectService projectService;
    private final WorkflowStepRepository stepRepository;
    private final AgentExecutionRepository agentExecutionRepository;
    private final ToolExecutionRepository toolExecutionRepository;
    private final WorkflowArtifactCodec codec;
    private final WorkflowEventStream eventStream;

    public WorkflowController(
            DevelopmentWorkflowService workflowService,
            ProjectService projectService,
            WorkflowStepRepository stepRepository,
            AgentExecutionRepository agentExecutionRepository,
            ToolExecutionRepository toolExecutionRepository,
            WorkflowArtifactCodec codec,
            WorkflowEventStream eventStream) {
        this.workflowService = workflowService;
        this.projectService = projectService;
        this.stepRepository = stepRepository;
        this.agentExecutionRepository = agentExecutionRepository;
        this.toolExecutionRepository = toolExecutionRepository;
        this.codec = codec;
        this.eventStream = eventStream;
    }

    /**
     * Legacy entry point: starts a workflow from a repository path instead of a project.
     *
     * @deprecated use {@code POST /api/projects/{projectId}/workflows}. Kept for one version so that
     *     clients can migrate; it resolves the project from the repository and refuses to guess when
     *     the answer is ambiguous.
     */
    @Deprecated(since = "project-domain")
    @PostMapping
    public ResponseEntity<WorkflowResponse> create(@Valid @RequestBody CreateWorkflowRequest request) {
        ProjectEntity project = resolveLegacyProject(request.gitlabProjectId());
        WorkflowEntity workflow = workflowService.createOrGetActive(project, request.jiraTicket());
        workflowService.startAsync(workflow.getId());
        return ResponseEntity.created(URI.create("/api/workflows/" + workflow.getId()))
                .body(WorkflowResponse.from(workflow));
    }

    /**
     * Legacy entry point for a free-form development request.
     *
     * @deprecated use {@code POST /api/projects/{projectId}/workflows/message}.
     */
    @Deprecated(since = "project-domain")
    @PostMapping("/message")
    public ResponseEntity<WorkflowResponse> createFromMessage(@Valid @RequestBody CreateMessageWorkflowRequest request) {
        ProjectEntity project = resolveLegacyProject(request.gitlabProjectId());
        WorkflowEntity workflow = workflowService.createFromMessage(project, request.message());
        workflowService.startAsync(workflow.getId());
        return ResponseEntity.created(URI.create("/api/workflows/" + workflow.getId()))
                .body(WorkflowResponse.from(workflow));
    }

    /**
     * Maps a repository path to the single project allowed to start work on it.
     *
     * <p>A repository is not unique across projects — that is what makes cloning useful — so this
     * refuses rather than picks. Both answers are 409 with the action to take, because both are a
     * configuration problem the caller can fix.
     */
    private ProjectEntity resolveLegacyProject(String gitlabProjectId) {
        List<ProjectEntity> candidates = projectService.findStartableByGitlabProject(gitlabProjectId);
        if (candidates.isEmpty()) {
            throw new IllegalStateException("No active project references the repository '" + gitlabProjectId
                    + "'; create one with POST /api/projects");
        }
        if (candidates.size() > 1) {
            throw new IllegalStateException("Several projects reference the repository '" + gitlabProjectId
                    + "'; use POST /api/projects/{projectId}/workflows to say which one");
        }
        return candidates.get(0);
    }

    @GetMapping
    public Page<WorkflowResponse> list(
            @RequestParam(required = false) WorkflowStatus status,
            @RequestParam(required = false) UUID projectId,
            @RequestParam(defaultValue = "false") boolean includeArchived,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return workflowService
                .list(status, projectId, includeArchived, PageRequest.of(page, Math.min(size, 100)))
                .map(WorkflowResponse::from);
    }

    /**
     * Streams every workflow change, for the list screen.
     *
     * <p>Declared before {@code /{id}} for readability only: a literal segment always wins over a
     * variable one in Spring's pattern comparison.
     */
    @GetMapping(path = "/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamAll() {
        return eventStream.subscribe(null);
    }

    /** Streams the changes of one workflow, for its detail screen. */
    @GetMapping(path = "/{id}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@PathVariable UUID id) {
        // Fail with the usual 404 rather than handing back a stream that will never emit anything.
        workflowService.get(id);
        return eventStream.subscribe(id);
    }

    @GetMapping("/{id}")
    public WorkflowDetailResponse get(@PathVariable UUID id) {
        WorkflowEntity workflow = workflowService.get(id);

        List<WorkflowDetailResponse.StepView> steps =
                stepRepository.findByWorkflowIdOrderBySequenceNumberAsc(id).stream()
                        .map(WorkflowDetailResponse.StepView::from)
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
