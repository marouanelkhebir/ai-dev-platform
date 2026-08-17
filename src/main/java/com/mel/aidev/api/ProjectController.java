package com.mel.aidev.api;

import com.mel.aidev.api.dto.CloneProjectRequest;
import com.mel.aidev.api.dto.CreateProjectMessageWorkflowRequest;
import com.mel.aidev.api.dto.CreateProjectWorkflowRequest;
import com.mel.aidev.api.dto.ProjectDashboardResponse;
import com.mel.aidev.api.dto.ProjectDetailResponse;
import com.mel.aidev.api.dto.ProjectRequest;
import com.mel.aidev.api.dto.ProjectResponse;
import com.mel.aidev.api.dto.WorkflowResponse;
import com.mel.aidev.persistence.entity.ProjectEntity;
import com.mel.aidev.persistence.entity.WorkflowEntity;
import com.mel.aidev.persistence.repository.WorkflowRepository;
import com.mel.aidev.project.ProjectConfigurationResolver;
import com.mel.aidev.project.ProjectDashboardService;
import com.mel.aidev.project.ProjectService;
import com.mel.aidev.workflow.DevelopmentWorkflowService;
import com.mel.aidev.workflow.WorkflowStatus;
import jakarta.validation.Valid;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** REST API of the projects: configuration, workflows, dashboard and lifecycle. */
@RestController
@RequestMapping("/api/projects")
public class ProjectController {

    private final ProjectService projectService;
    private final ProjectConfigurationResolver resolver;
    private final ProjectDashboardService dashboardService;
    private final DevelopmentWorkflowService workflowService;
    private final WorkflowRepository workflowRepository;

    public ProjectController(
            ProjectService projectService,
            ProjectConfigurationResolver resolver,
            ProjectDashboardService dashboardService,
            DevelopmentWorkflowService workflowService,
            WorkflowRepository workflowRepository) {
        this.projectService = projectService;
        this.resolver = resolver;
        this.dashboardService = dashboardService;
        this.workflowService = workflowService;
        this.workflowRepository = workflowRepository;
    }

    // ------------------------------------------------------------------- CRUD

    @PostMapping
    public ResponseEntity<ProjectDetailResponse> create(@Valid @RequestBody ProjectRequest request) {
        ProjectEntity project = projectService.create(request.toDefinition());
        return ResponseEntity.created(URI.create("/api/projects/" + project.getId())).body(detail(project));
    }

    @GetMapping
    public Page<ProjectResponse> list(
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "false") boolean activeOnly,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return projectService
                .search(q, activeOnly, PageRequest.of(page, Math.min(size, 100)))
                .map(this::summary);
    }

    @GetMapping("/{id}")
    public ProjectDetailResponse get(@PathVariable UUID id) {
        return detail(projectService.get(id));
    }

    @PutMapping("/{id}")
    public ProjectDetailResponse update(@PathVariable UUID id, @Valid @RequestBody ProjectRequest request) {
        return detail(projectService.update(id, request.toDefinition()));
    }

    /**
     * Archives the project, or deletes it for good with {@code ?force=true}.
     *
     * <p>Archiving is the default because it keeps the audit trail; the permanent delete only works
     * on a project that holds no workflow at all.
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<ProjectDetailResponse> delete(
            @PathVariable UUID id, @RequestParam(defaultValue = "false") boolean force) {
        if (force) {
            projectService.delete(id);
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.ok(detail(projectService.archive(id)));
    }

    @PostMapping("/{id}/restore")
    public ProjectDetailResponse restore(@PathVariable UUID id) {
        return detail(projectService.restore(id));
    }

    @PostMapping("/{id}/clone")
    public ResponseEntity<ProjectDetailResponse> clone(
            @PathVariable UUID id, @Valid @RequestBody CloneProjectRequest request) {
        ProjectEntity clone = projectService.clone(
                id, request.name(), request.gitlabProject(), request.jiraProjectKey(), request.dockerImage());
        return ResponseEntity.created(URI.create("/api/projects/" + clone.getId())).body(detail(clone));
    }

    // -------------------------------------------------------------- variables

    @PutMapping("/{id}/variables/{name}")
    public ResponseEntity<Map<String, String>> putVariable(
            @PathVariable UUID id, @PathVariable String name, @RequestBody Map<String, String> body) {
        projectService.putVariable(id, name, body == null ? null : body.get("value"));
        return ResponseEntity.ok(resolver.variables(id));
    }

    @DeleteMapping("/{id}/variables/{name}")
    public ResponseEntity<Void> deleteVariable(@PathVariable UUID id, @PathVariable String name) {
        projectService.deleteVariable(id, name);
        return ResponseEntity.noContent().build();
    }

    // --------------------------------------------------------------- workflows

    @PostMapping("/{projectId}/workflows")
    public ResponseEntity<WorkflowResponse> createWorkflow(
            @PathVariable UUID projectId, @Valid @RequestBody CreateProjectWorkflowRequest request) {
        ProjectEntity project = projectService.get(projectId);
        WorkflowEntity workflow = workflowService.createOrGetActive(project, request.jiraTicket());
        workflowService.startAsync(workflow.getId());
        return ResponseEntity.created(URI.create("/api/workflows/" + workflow.getId()))
                .body(WorkflowResponse.from(workflow));
    }

    @PostMapping("/{projectId}/workflows/message")
    public ResponseEntity<WorkflowResponse> createWorkflowFromMessage(
            @PathVariable UUID projectId, @Valid @RequestBody CreateProjectMessageWorkflowRequest request) {
        ProjectEntity project = projectService.get(projectId);
        WorkflowEntity workflow = workflowService.createFromMessage(project, request.message());
        workflowService.startAsync(workflow.getId());
        return ResponseEntity.created(URI.create("/api/workflows/" + workflow.getId()))
                .body(WorkflowResponse.from(workflow));
    }

    @GetMapping("/{projectId}/workflows")
    public Page<WorkflowResponse> listWorkflows(
            @PathVariable UUID projectId,
            @RequestParam(required = false) WorkflowStatus status,
            @RequestParam(required = false) String jiraTicket,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(defaultValue = "false") boolean includeArchived,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        projectService.get(projectId);
        return workflowService
                .listForProject(
                        projectId,
                        status,
                        jiraTicket,
                        from,
                        to,
                        includeArchived,
                        PageRequest.of(page, Math.min(size, 100)))
                .map(WorkflowResponse::from);
    }

    /**
     * Deletes a workflow of this project.
     *
     * <p>The merge request on GitLab is never touched, and the LLM accounting rows survive: they
     * belong to the cost history of the project, not to the workflow.
     */
    @DeleteMapping("/{projectId}/workflows/{workflowId}")
    public ResponseEntity<Void> deleteWorkflow(
            @PathVariable UUID projectId,
            @PathVariable UUID workflowId,
            @RequestHeader(value = "X-Actor", required = false) String actor) {
        workflowService.delete(projectId, workflowId, actor == null || actor.isBlank() ? "unknown" : actor);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{projectId}/workflows/{workflowId}/archive")
    public WorkflowResponse archiveWorkflow(
            @PathVariable UUID projectId,
            @PathVariable UUID workflowId,
            @RequestHeader(value = "X-Actor", required = false) String actor) {
        return WorkflowResponse.from(
                workflowService.archive(projectId, workflowId, actor == null || actor.isBlank() ? "unknown" : actor));
    }

    @PostMapping("/{projectId}/workflows/{workflowId}/restore")
    public WorkflowResponse restoreWorkflow(@PathVariable UUID projectId, @PathVariable UUID workflowId) {
        return WorkflowResponse.from(workflowService.restore(projectId, workflowId));
    }

    // --------------------------------------------------------------- dashboard

    @GetMapping("/{id}/dashboard")
    public ProjectDashboardResponse dashboard(
            @PathVariable UUID id,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to) {
        projectService.get(id);
        return dashboardService.dashboard(id, from, to);
    }

    // --------------------------------------------------------------- internals

    private ProjectResponse summary(ProjectEntity project) {
        Optional<WorkflowEntity> last = workflowRepository.findFirstByProjectIdOrderByCreatedAtDesc(project.getId());
        return ProjectResponse.from(
                project,
                projectService.countWorkflows(project.getId()),
                last.map(WorkflowEntity::getCreatedAt).orElse(null),
                last.map(WorkflowEntity::getStatus).orElse(null));
    }

    private ProjectDetailResponse detail(ProjectEntity project) {
        List<List<String>> commands = resolver.commands(project);
        return new ProjectDetailResponse(
                summary(project),
                project.getBranchPrefix(),
                splitCsv(project.getProtectedBranches()),
                commands.get(0),
                commands.get(1),
                commands.get(2),
                project.getRetentionDays(),
                resolver.variables(project.getId()),
                resolver.pinnedModels(project.getId()),
                projectService.countActiveWorkflows(project.getId()),
                resolver.resolve(project));
    }

    private static List<String> splitCsv(String csv) {
        if (csv == null || csv.isBlank()) {
            return List.of();
        }
        return java.util.Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .toList();
    }
}
