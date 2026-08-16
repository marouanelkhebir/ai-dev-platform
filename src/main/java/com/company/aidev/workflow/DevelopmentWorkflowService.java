package com.company.aidev.workflow;

import com.company.aidev.config.AsyncConfig;
import com.company.aidev.config.GitLabProperties;
import com.company.aidev.gitlab.GitLabClient;
import com.company.aidev.gitlab.model.Pipeline;
import com.company.aidev.gitlab.model.PipelineStatus;
import com.company.aidev.observability.PlatformMetrics;
import com.company.aidev.persistence.entity.WorkflowEntity;
import com.company.aidev.persistence.repository.WorkflowRepository;
import com.company.aidev.security.BranchPolicy;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
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
    private final GitLabClient gitLabClient;
    private final GitLabProperties gitLabProperties;
    private final BranchPolicy branchPolicy;
    private final PlatformMetrics metrics;

    public DevelopmentWorkflowService(
            WorkflowRepository workflowRepository,
            WorkflowEngine engine,
            WorkflowStateStore stateStore,
            GitLabClient gitLabClient,
            GitLabProperties gitLabProperties,
            BranchPolicy branchPolicy,
            PlatformMetrics metrics) {
        this.workflowRepository = workflowRepository;
        this.engine = engine;
        this.stateStore = stateStore;
        this.gitLabClient = gitLabClient;
        this.gitLabProperties = gitLabProperties;
        this.branchPolicy = branchPolicy;
        this.metrics = metrics;
    }

    /**
     * Creates a workflow for a ticket, or returns the one already running for it.
     *
     * <p>Returning the existing workflow instead of creating a second one is what makes the Jira
     * webhook safe to replay: a label toggled twice must not open two merge requests.
     */
    @Transactional
    public WorkflowEntity createOrGetActive(String jiraTicket, String gitlabProjectId) {
        List<WorkflowEntity> active = workflowRepository.findActiveByJiraTicket(jiraTicket, TERMINAL_STATUSES);
        if (!active.isEmpty()) {
            WorkflowEntity existing = active.get(0);
            log.info("Workflow {} already active for ticket {}", existing.getId(), jiraTicket);
            return existing;
        }

        WorkflowEntity workflow = new WorkflowEntity(
                UUID.randomUUID(), jiraTicket, gitlabProjectId, gitLabProperties.defaultTargetBranch());
        workflow.setBranch(branchPolicy.branchFor(jiraTicket));
        WorkflowEntity saved = workflowRepository.save(workflow);
        metrics.workflowStarted(gitlabProjectId);
        log.info("Workflow {} created for ticket {} on project {}", saved.getId(), jiraTicket, gitlabProjectId);
        return saved;
    }

    /** Creates a new workflow from a free-form request. Direct requests deliberately are not deduplicated. */
    @Transactional
    public WorkflowEntity createFromMessage(String message, String gitlabProjectId) {
        String requestId = "MSG-" + UUID.randomUUID().toString().replace("-", "");
        WorkflowEntity workflow = new WorkflowEntity(
                UUID.randomUUID(), requestId, gitlabProjectId, gitLabProperties.defaultTargetBranch());
        workflow.setSourceMessage(message);
        workflow.setBranch(branchPolicy.branchFor("message/" + requestId));
        WorkflowEntity saved = workflowRepository.save(workflow);
        metrics.workflowStarted(gitlabProjectId);
        log.info("Workflow {} created from a direct request on project {}", saved.getId(), gitlabProjectId);
        return saved;
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
    public Page<WorkflowEntity> list(WorkflowStatus status, Pageable pageable) {
        return status == null
                ? workflowRepository.findAllByOrderByCreatedAtDesc(pageable)
                : workflowRepository.findAllByStatusOrderByCreatedAtDesc(status, pageable);
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
        log.info("Workflow {} cancelled", workflowId);
        return workflowRepository.save(workflow);
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

    /**
     * Applies a finished pipeline to the matching workflow.
     *
     * <p>Returns the workflow to advance rather than advancing it here: {@code @Async} does not apply
     * to a call made from inside the same bean, and running a multi-minute step inside this
     * transaction would hold a connection for the whole review round.
     *
     * @return the workflow id to advance, or empty when nothing matched
     */
    @Transactional
    public Optional<UUID> onPipelineEvent(String gitlabProject, String ref, long pipelineId, String status) {
        Optional<WorkflowEntity> found =
                workflowRepository.findFirstByGitlabProjectAndBranchOrderByCreatedAtDesc(gitlabProject, ref);
        if (found.isEmpty()) {
            log.debug("No workflow for pipeline on {}@{}", gitlabProject, ref);
            return Optional.empty();
        }
        WorkflowEntity workflow = found.get();
        if (workflow.getStatus() != WorkflowStatus.WAITING_PIPELINE) {
            log.debug(
                    "Workflow {} is in status {}, ignoring the pipeline event", workflow.getId(), workflow.getStatus());
            return Optional.empty();
        }

        Pipeline pipeline = gitLabClient
                .getPipeline(gitlabProject, pipelineId)
                .orElseGet(() -> new Pipeline(pipelineId, PipelineStatus.from(status), ref, null, ""));
        if (!pipeline.status().isFinished()) {
            return Optional.empty();
        }

        UUID workflowId = workflow.getId();
        engine.onPipelineFinished(workflow, pipeline);
        return Optional.of(workflowId);
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
     * Re-checks a pipeline the platform may have missed the webhook for.
     *
     * <p>Webhooks get lost — a deploy at the wrong moment, a network blip, a GitLab retry budget
     * exhausted. Without this poll a workflow would sit in {@code WAITING_PIPELINE} forever.
     *
     * @return the workflow id to advance, or empty when the pipeline is not finished
     */
    @Transactional
    public Optional<UUID> pollPipeline(UUID workflowId) {
        WorkflowEntity workflow = get(workflowId);
        if (workflow.getStatus() != WorkflowStatus.WAITING_PIPELINE || workflow.getBranch() == null) {
            return Optional.empty();
        }
        Optional<Pipeline> pipeline =
                gitLabClient.getLatestPipelineForRef(workflow.getGitlabProject(), workflow.getBranch());
        if (pipeline.isEmpty() || !pipeline.get().status().isFinished()) {
            return Optional.empty();
        }
        log.info("Pipeline {} recovered by polling for workflow {}", pipeline.get().id(), workflowId);
        engine.onPipelineFinished(workflow, pipeline.get());
        return Optional.of(workflowId);
    }

    /** Fails a workflow that waited for its pipeline beyond the configured timeout. */
    @Transactional
    public void failPipelineTimeout(UUID workflowId) {
        WorkflowEntity workflow = get(workflowId);
        if (workflow.getStatus() != WorkflowStatus.WAITING_PIPELINE) {
            return;
        }
        workflow.setStatus(WorkflowStatus.FAILED);
        workflow.setFailureReason("The GitLab pipeline did not finish within "
                + gitLabProperties.pipeline().timeout());
        workflow.setClaimedAt(null);
        workflowRepository.save(workflow);
        metrics.workflowFailed(workflow.getGitlabProject(), "pipeline-timeout");
        log.warn("Workflow {} failed on pipeline timeout", workflowId);
    }

    /** Exposed so callers can find the workflows the scheduler should look at. */
    @Transactional(readOnly = true)
    public List<UUID> findWorkflowsWaitingForPipeline(java.time.Instant before) {
        return workflowRepository.findIdsStuckInStatus(WorkflowStatus.WAITING_PIPELINE, before);
    }

    @Transactional(readOnly = true)
    public List<UUID> findRunnableWorkflows(java.time.Instant staleBefore) {
        return workflowRepository.findRunnableIds(WorkflowStatus.runnableStatuses(), staleBefore);
    }
}
