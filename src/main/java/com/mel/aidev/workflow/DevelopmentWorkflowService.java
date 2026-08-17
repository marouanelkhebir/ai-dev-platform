package com.mel.aidev.workflow;

import com.mel.aidev.config.AsyncConfig;
import com.mel.aidev.config.GitLabProperties;
import com.mel.aidev.observability.PlatformMetrics;
import com.mel.aidev.persistence.entity.ProjectEntity;
import com.mel.aidev.persistence.entity.WorkflowEntity;
import com.mel.aidev.persistence.repository.WorkflowRepository;
import com.mel.aidev.persistence.repository.WorkflowSpecifications;
import com.mel.aidev.project.ProjectConfiguration;
import com.mel.aidev.project.ProjectConfigurationResolver;
import com.mel.aidev.project.ProjectValidationException;
import com.mel.aidev.project.WorkflowLaunchConfiguration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Application service driving the workflows.
 *
 * <p>Everything that starts, resumes, retries or cancels a workflow goes through here: the REST API,
 * the Jira webhook, the GitLab webhook and the scheduler. The engine itself stays free of entry-point
 * concerns.
 */
@Service
public class DevelopmentWorkflowService {

    private static final Logger log = LoggerFactory.getLogger(DevelopmentWorkflowService.class);

    private static final EnumSet<WorkflowStatus> TERMINAL_STATUSES = EnumSet.of(
            WorkflowStatus.DONE,
            WorkflowStatus.FAILED,
            WorkflowStatus.CANCELLED,
            WorkflowStatus.NEEDS_CLARIFICATION);

    private final WorkflowRepository workflowRepository;
    private final WorkflowEngine engine;
    private final WorkflowStateStore stateStore;
    private final GitLabProperties gitLabProperties;
    private final WorkflowLaunchConfiguration launchConfiguration;
    private final PlatformMetrics metrics;
    private final ApplicationEventPublisher events;

    public DevelopmentWorkflowService(
            WorkflowRepository workflowRepository,
            WorkflowEngine engine,
            WorkflowStateStore stateStore,
            GitLabProperties gitLabProperties,
            WorkflowLaunchConfiguration launchConfiguration,
            PlatformMetrics metrics,
            ApplicationEventPublisher events) {
        this.workflowRepository = workflowRepository;
        this.engine = engine;
        this.stateStore = stateStore;
        this.gitLabProperties = gitLabProperties;
        this.launchConfiguration = launchConfiguration;
        this.metrics = metrics;
        this.events = events;
    }

    /**
     * Creates a workflow for a ticket of a project, or returns the one already running for it.
     *
     * <p>Returning the existing workflow instead of creating a second one is what makes the Jira
     * webhook safe to replay: a label toggled twice must not open two merge requests. The check is
     * global to the ticket rather than scoped to the project, because the guarantee wanted is about
     * the ticket — two projects pointing at the same repository must not both work on it.
     */
    @Transactional
    public WorkflowEntity createOrGetActive(ProjectEntity project, String jiraTicket) {
        assertStartable(project);
        assertTicketBelongsToProject(project, jiraTicket);

        List<WorkflowEntity> active = workflowRepository.findActiveByJiraTicket(jiraTicket, TERMINAL_STATUSES);
        if (!active.isEmpty()) {
            WorkflowEntity existing = active.get(0);
            log.info("Workflow {} already active for ticket {}", existing.getId(), jiraTicket);
            return existing;
        }

        WorkflowEntity workflow = newWorkflow(project, jiraTicket);
        WorkflowEntity saved = workflowRepository.save(workflow);
        metrics.workflowStarted(project.getName());
        events.publishEvent(new WorkflowChangedEvent(saved));
        log.info("Workflow {} created for ticket {} on project {}", saved.getId(), jiraTicket, project.getName());
        return saved;
    }

    /** Creates a new workflow from a free-form request. Direct requests deliberately are not deduplicated. */
    @Transactional
    public WorkflowEntity createFromMessage(ProjectEntity project, String message) {
        assertStartable(project);
        String requestId = "MSG-" + UUID.randomUUID().toString().replace("-", "");

        WorkflowEntity workflow = newWorkflow(project, requestId);
        workflow.setSourceMessage(message);
        // The extra segment keeps direct requests recognisable in the branch list of the repository,
        // where a bare MSG-... identifier would say nothing to a human browsing it.
        workflow.setBranch(launchConfiguration.of(workflow).branchFor("message/" + requestId));
        WorkflowEntity saved = workflowRepository.save(workflow);
        metrics.workflowStarted(project.getName());
        events.publishEvent(new WorkflowChangedEvent(saved));
        log.info("Workflow {} created from a direct request on project {}", saved.getId(), project.getName());
        return saved;
    }

    /**
     * Builds the workflow and freezes the configuration it will run with.
     *
     * <p>The freeze happens here, before the first step: everything the engine reads afterwards —
     * image, branch prefix, commands, models — is the snapshot, so editing the project later changes
     * the next workflow and never this one.
     */
    private WorkflowEntity newWorkflow(ProjectEntity project, String jiraTicket) {
        WorkflowEntity workflow = new WorkflowEntity(
                UUID.randomUUID(),
                project.getId(),
                jiraTicket,
                project.getGitlabProject(),
                gitLabProperties.defaultTargetBranch());
        ProjectConfiguration configuration = launchConfiguration.freeze(workflow, project);
        workflow.setBaseBranch(configuration.baseBranch());
        workflow.setBranch(configuration.branchFor(jiraTicket));
        return workflow;
    }

    private void assertStartable(ProjectEntity project) {
        if (!project.isStartable()) {
            throw new IllegalStateException(
                    "Project " + project.getName() + " is archived or inactive and cannot start a workflow");
        }
    }

    /**
     * Refuses a ticket that does not belong to the Jira project configured on the project.
     *
     * <p>A project without a Jira key accepts no ticket at all: it exists for free-form requests,
     * and silently accepting {@code BANK-1245} on it would attach work to the wrong repository.
     */
    private void assertTicketBelongsToProject(ProjectEntity project, String jiraTicket) {
        String expected = ProjectConfigurationResolver.normalizedJiraKey(project);
        if (expected == null) {
            throw new ProjectValidationException("Project " + project.getName()
                    + " has no Jira project key; it only accepts free-form requests (POST .../workflows/message)");
        }
        String actual = jiraTicket == null || !jiraTicket.contains("-")
                ? null
                : jiraTicket.substring(0, jiraTicket.indexOf('-')).toUpperCase(Locale.ROOT);
        if (!expected.equals(actual)) {
            throw new ProjectValidationException("Ticket " + jiraTicket + " does not belong to the Jira project "
                    + expected + " configured on " + project.getName());
        }
    }

    /** Runs the workflow on the executor; returns immediately. */
    @Async(AsyncConfig.WORKFLOW_EXECUTOR)
    public void startAsync(UUID workflowId) {
        engine.advance(workflowId);
    }

    @Transactional(readOnly = true)
    public WorkflowEntity get(UUID workflowId) {
        return workflowRepository.findById(workflowId).orElseThrow(() -> new WorkflowNotFoundException(workflowId));
    }

    @Transactional(readOnly = true)
    public Page<WorkflowEntity> list(WorkflowStatus status, UUID projectId, boolean includeArchived, Pageable pageable) {
        return listForProject(projectId, status, null, null, null, includeArchived, pageable);
    }

    /** Workflows of one project, with the filters of the project screen. */
    @Transactional(readOnly = true)
    public Page<WorkflowEntity> listForProject(
            UUID projectId,
            WorkflowStatus status,
            String jiraTicket,
            Instant from,
            Instant to,
            boolean includeArchived,
            Pageable pageable) {
        Pageable sorted = PageRequest.of(
                pageable.getPageNumber(), pageable.getPageSize(), Sort.by(Sort.Direction.DESC, "createdAt"));
        return workflowRepository.findAll(
                WorkflowSpecifications.filtered(projectId, status, jiraTicket, from, to, includeArchived), sorted);
    }

    /**
     * Reads a workflow through its project.
     *
     * <p>A workflow belonging to another project answers 404 rather than 403: telling a caller that
     * an id exists somewhere else is information it did not have.
     */
    @Transactional(readOnly = true)
    public WorkflowEntity getInProject(UUID projectId, UUID workflowId) {
        return workflowRepository
                .findByIdAndProjectId(workflowId, projectId)
                .orElseThrow(() -> new WorkflowNotFoundException(workflowId));
    }

    /**
     * Deletes a workflow and everything the schema cascades from it.
     *
     * <p>Two things deliberately survive: the merge request on GitLab, which the platform has no
     * business erasing while a human may still be reading it, and the {@code llm_execution} rows,
     * which carry the cost accounting of the project and are keyed by project, not by workflow.
     */
    @Transactional
    public void delete(UUID projectId, UUID workflowId, String actor) {
        WorkflowEntity workflow = getInProject(projectId, workflowId);
        if (!workflow.getStatus().isTerminal()) {
            throw new IllegalStateException("Workflow " + workflowId + " is still running (" + workflow.getStatus()
                    + "); cancel it before deleting it");
        }
        log.info(
                "Workflow deleted id={} project={} ticket={} status={} mergeRequest={} by={}",
                workflowId,
                projectId,
                workflow.getJiraTicket(),
                workflow.getStatus(),
                workflow.getMergeRequestUrl(),
                actor);
        workflowRepository.delete(workflow);
    }

    /** Hides a workflow from the default listings while keeping its audit trail intact. */
    @Transactional
    public WorkflowEntity archive(UUID projectId, UUID workflowId, String actor) {
        WorkflowEntity workflow = getInProject(projectId, workflowId);
        if (!workflow.getStatus().isTerminal()) {
            throw new IllegalStateException("Workflow " + workflowId + " is still running (" + workflow.getStatus()
                    + "); cancel it before archiving it");
        }
        workflow.archive();
        WorkflowEntity saved = workflowRepository.save(workflow);
        events.publishEvent(new WorkflowChangedEvent(saved));
        log.info("Workflow archived id={} project={} by={}", workflowId, projectId, actor);
        return saved;
    }

    @Transactional
    public WorkflowEntity restore(UUID projectId, UUID workflowId) {
        WorkflowEntity workflow = getInProject(projectId, workflowId);
        workflow.restore();
        WorkflowEntity saved = workflowRepository.save(workflow);
        events.publishEvent(new WorkflowChangedEvent(saved));
        return saved;
    }

    /**
     * Restarts a stopped workflow.
     *
     * <p>The attempt counters are reset, because a human is explicitly asking for another round —
     * usually after clarifying the ticket or fixing something by hand.
     */
    @Transactional
    public WorkflowEntity retry(UUID workflowId) {
        WorkflowEntity workflow = get(workflowId);
        if (!workflow.getStatus().isTerminal()) {
            throw new IllegalStateException(
                    "Workflow " + workflowId + " is still running (" + workflow.getStatus() + ")");
        }
        WorkflowStatus resumeAt = workflow.getTicketAnalysisJson() == null
                ? WorkflowStatus.CREATED
                : (workflow.getTechnicalPlanJson() == null ? WorkflowStatus.PLANNING : WorkflowStatus.DEVELOPING);

        workflow.setStatus(resumeAt);
        workflow.setFailureReason(null);
        workflow.setClaimedAt(null);
        workflow.resetAttempts();
        WorkflowEntity saved = workflowRepository.save(workflow);
        events.publishEvent(new WorkflowChangedEvent(saved));
        log.info("Workflow {} retried from {}", workflowId, resumeAt);
        return saved;
    }

    /**
     * Accepts the information requested by the analyst and restarts from the analysis gate.
     * The original request and the clarification stay separate so the audit remains intelligible.
     */
    @Transactional
    public WorkflowEntity clarify(UUID workflowId, String clarification) {
        WorkflowEntity workflow = get(workflowId);
        if (workflow.getStatus() != WorkflowStatus.NEEDS_CLARIFICATION) {
            throw new IllegalStateException(
                    "Workflow " + workflowId + " is not waiting for clarification (" + workflow.getStatus() + ")");
        }
        workflow.setHumanClarification(clarification.trim());
        workflow.setStatus(WorkflowStatus.ANALYZING_JIRA);
        workflow.setFailureReason(null);
        workflow.setClaimedAt(null);
        workflow.resetAttempts();
        WorkflowEntity saved = workflowRepository.save(workflow);
        events.publishEvent(new WorkflowChangedEvent(saved));
        stateStore.recordTransition(
                workflowId,
                WorkflowStatus.NEEDS_CLARIFICATION,
                WorkflowStatus.ANALYZING_JIRA,
                "Human clarification submitted; analysis restarted");
        log.info("Workflow {} resumed after human clarification", workflowId);
        return saved;
    }

    @Transactional
    public WorkflowEntity cancel(UUID workflowId) {
        WorkflowEntity workflow = get(workflowId);
        if (workflow.getStatus().isTerminal()) {
            return workflow;
        }
        workflow.setStatus(WorkflowStatus.CANCELLED);
        workflow.setFailureReason("Cancelled");
        workflow.setClaimedAt(null);
        WorkflowEntity saved = workflowRepository.save(workflow);
        events.publishEvent(new WorkflowChangedEvent(saved));
        log.info("Workflow {} cancelled", workflowId);
        return saved;
    }

    /** Records the human decision that closes the workflow. The merge itself stays manual. */
    @Transactional
    public WorkflowEntity approve(UUID workflowId, String approver) {
        WorkflowEntity workflow = get(workflowId);
        if (workflow.getStatus() != WorkflowStatus.WAITING_HUMAN_APPROVAL) {
            throw new IllegalStateException(
                    "Workflow " + workflowId + " is not waiting for approval (" + workflow.getStatus() + ")");
        }
        engine.onHumanApproval(workflow, approver);
        return get(workflowId);
    }

    /** Resumes a workflow whose merge request was approved or merged by a human. */
    @Transactional
    public boolean onMergeRequestApproved(String gitlabProject, long mergeRequestIid, String approver) {
        Optional<WorkflowEntity> found =
                workflowRepository.findFirstByMergeRequestIidAndGitlabProjectOrderByCreatedAtDesc(
                        mergeRequestIid, gitlabProject);
        if (found.isEmpty() || found.get().getStatus() != WorkflowStatus.WAITING_HUMAN_APPROVAL) {
            return false;
        }
        engine.onHumanApproval(found.get(), approver);
        return true;
    }

    /**
     * Ids of the workflows the scheduler may pick up, oldest first and at most {@code limit} of them.
     *
     * <p>The cap is what keeps a recovery tick proportionate: after an outage the whole table is
     * runnable, and submitting all of it would only fill the executor queue so the rest is shed.
     * Draining a slice per tick reaches the same place without the burst.
     */
    @Transactional(readOnly = true)
    public List<UUID> findRunnableWorkflows(Instant staleBefore, int limit) {
        return workflowRepository.findRunnableIds(
                WorkflowStatus.runnableStatuses(), staleBefore, PageRequest.of(0, limit));
    }
}
