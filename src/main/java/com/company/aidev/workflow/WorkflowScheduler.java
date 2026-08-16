package com.company.aidev.workflow;

import com.company.aidev.config.WorkflowProperties;
import com.company.aidev.persistence.repository.WebhookEventRepository;
import com.company.aidev.sandbox.SandboxManager;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Recovery loops.
 *
 * <p>The happy path is event-driven: a Jira webhook starts a workflow, a GitLab webhook resumes it.
 * These scheduled tasks exist for everything that goes wrong around that — a webhook that never
 * arrived, a pod killed mid-step, a container left behind by a crash. Without them the platform is
 * one lost HTTP call away from a workflow stuck forever.
 */
@Component
@ConditionalOnProperty(name = "workflow.scheduler.enabled", havingValue = "true", matchIfMissing = true)
public class WorkflowScheduler {

    private static final Logger log = LoggerFactory.getLogger(WorkflowScheduler.class);

    private final DevelopmentWorkflowService workflowService;
    private final WorkflowProperties workflowProperties;
    private final SandboxManager sandboxManager;
    private final WebhookEventRepository webhookEventRepository;

    public WorkflowScheduler(
            DevelopmentWorkflowService workflowService,
            WorkflowProperties workflowProperties,
            SandboxManager sandboxManager,
            WebhookEventRepository webhookEventRepository) {
        this.workflowService = workflowService;
        this.workflowProperties = workflowProperties;
        this.sandboxManager = sandboxManager;
        this.webhookEventRepository = webhookEventRepository;
    }

    /**
     * Picks up workflows that are runnable but nobody is running: created before the executor had a
     * slot, or abandoned when the previous instance died mid-step.
     */
    @Scheduled(fixedDelayString = "${workflow.scheduler.resume-interval:PT60S}")
    public void resumeRunnableWorkflows() {
        Instant staleBefore = Instant.now().minus(workflowProperties.staleWorkflowTimeout());
        List<UUID> ids = workflowService.findRunnableWorkflows(staleBefore);
        if (ids.isEmpty()) {
            return;
        }
        log.info("Resuming {} runnable workflow(s)", ids.size());
        ids.forEach(workflowService::startAsync);
    }

    /** Removes containers that outlived their workflow. */
    @Scheduled(fixedDelayString = "${workflow.scheduler.sandbox-janitor-interval:PT600S}")
    public void cleanupSandboxes() {
        int removed = sandboxManager.cleanupStaleSandboxes();
        if (removed > 0) {
            log.info("Sandbox janitor removed {} stale container(s)", removed);
        }
    }

    /** Keeps the webhook idempotency ledger from growing without bound. */
    @Scheduled(cron = "${workflow.scheduler.webhook-cleanup-cron:0 30 3 * * *}")
    @Transactional
    public void cleanupWebhookLedger() {
        int deleted = webhookEventRepository.deleteOlderThan(Instant.now().minus(java.time.Duration.ofDays(14)));
        if (deleted > 0) {
            log.info("Removed {} webhook ledger entries older than 14 days", deleted);
        }
    }
}
