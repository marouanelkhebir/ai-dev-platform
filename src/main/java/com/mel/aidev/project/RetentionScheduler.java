package com.mel.aidev.project;

import com.mel.aidev.workflow.WorkflowStepLogService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs the retention purge once a night.
 *
 * <p>Off-hours and single-shot: the purge writes on the same tables the engine reads, and there is
 * nothing to gain from doing it while the platform is busy. Missing a night costs nothing either —
 * the job is idempotent and simply catches up.
 */
@Component
@ConditionalOnProperty(name = "workflow.retention.enabled", havingValue = "true", matchIfMissing = true)
public class RetentionScheduler {

    private static final Logger log = LoggerFactory.getLogger(RetentionScheduler.class);

    private final RetentionService retentionService;
    private final WorkflowStepLogService stepLogService;

    public RetentionScheduler(RetentionService retentionService, WorkflowStepLogService stepLogService) {
        this.retentionService = retentionService;
        this.stepLogService = stepLogService;
    }

    @Scheduled(cron = "${workflow.retention.cron:0 30 3 * * *}")
    public void purge() {
        try {
            retentionService.purgeAll();
        } catch (RuntimeException e) {
            // A failed purge must not take the scheduler down: the next run picks up where it stopped.
            log.error("Retention purge failed", e);
        }
        try {
            // Separate pass, and separate failure: step logs live on their own, shorter schedule
            // because they are the bulk of the volume, and losing them costs a debugging trail, not
            // the audit trail.
            stepLogService.purgeOlderThanRetention();
        } catch (RuntimeException e) {
            log.error("Step log purge failed", e);
        }
    }
}
