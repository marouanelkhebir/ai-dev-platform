package com.mel.aidev.workflow;

import com.mel.aidev.config.WorkflowProperties;
import com.mel.aidev.persistence.entity.WorkflowEntity;
import com.mel.aidev.persistence.entity.WorkflowStepEntity;
import com.mel.aidev.persistence.repository.WorkflowRepository;
import com.mel.aidev.persistence.repository.WorkflowStepRepository;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Short transactions around the workflow row.
 *
 * <p>Steps take minutes: holding a database transaction (or a pessimistic lock) for their duration
 * would pin a connection and block every other reader. Instead, the engine claims a workflow with a
 * timestamp, works detached, and relies on the {@code @Version} column to detect a concurrent writer.
 */
@Component
public class WorkflowStateStore {

    private static final Logger log = LoggerFactory.getLogger(WorkflowStateStore.class);

    private final WorkflowRepository workflowRepository;
    private final WorkflowStepRepository stepRepository;
    private final WorkflowProperties properties;
    private final ApplicationEventPublisher events;

    public WorkflowStateStore(
            WorkflowRepository workflowRepository,
            WorkflowStepRepository stepRepository,
            WorkflowProperties properties,
            ApplicationEventPublisher events) {
        this.workflowRepository = workflowRepository;
        this.stepRepository = stepRepository;
        this.properties = properties;
        this.events = events;
    }

    /**
     * Attempts to take ownership of a workflow.
     *
     * @return the claimed workflow, or empty when another worker owns it or it is not runnable
     */
    @Transactional
    public Optional<WorkflowEntity> claim(UUID workflowId) {
        Optional<WorkflowEntity> found = workflowRepository.findByIdForUpdate(workflowId);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        WorkflowEntity workflow = found.get();
        if (!workflow.getStatus().isRunnable()) {
            return Optional.empty();
        }
        Instant staleBefore = Instant.now().minus(properties.staleWorkflowTimeout());
        if (workflow.getClaimedAt() != null && workflow.getClaimedAt().isAfter(staleBefore)) {
            log.debug("Workflow {} is already claimed at {}", workflowId, workflow.getClaimedAt());
            return Optional.empty();
        }
        workflow.setClaimedAt(Instant.now());
        return Optional.of(workflowRepository.saveAndFlush(workflow));
    }

    /**
     * Extends the claim of a workflow whose run is still in progress.
     *
     * <p>Without this, a run that outlives {@code workflow.stale-workflow-timeout} — three
     * development rounds with a Maven build each will do it — looks abandoned to every other worker.
     * A second engine would then claim it, create a second sandbox and push the same branch twice.
     * The engine calls this between steps, so the claim stays fresh for as long as work is happening
     * and goes stale within one timeout of the worker actually dying.
     */
    @Transactional
    public void heartbeat(UUID workflowId) {
        try {
            workflowRepository.refreshClaim(workflowId, Instant.now());
        } catch (RuntimeException e) {
            // A missed heartbeat costs one re-claim at worst; failing the step over it costs the run.
            log.warn("Unable to refresh the claim of workflow {}: {}", workflowId, e.toString());
        }
    }

    @Transactional
    public void release(UUID workflowId) {
        workflowRepository.findById(workflowId).ifPresent(workflow -> {
            workflow.setClaimedAt(null);
            workflowRepository.save(workflow);
        });
    }

    /**
     * Persists the detached workflow.
     *
     * @throws OptimisticLockingFailureException when another writer changed the row meanwhile
     */
    @Transactional
    public WorkflowEntity save(WorkflowEntity workflow) {
        workflow.touch();
        WorkflowEntity saved = workflowRepository.saveAndFlush(workflow);
        events.publishEvent(new WorkflowChangedEvent(saved));
        return saved;
    }

    @Transactional
    public Optional<WorkflowEntity> find(UUID workflowId) {
        return workflowRepository.findById(workflowId);
    }

    @Transactional
    public WorkflowStepEntity beginStep(UUID workflowId, WorkflowStatus from) {
        int sequence = stepRepository.findMaxSequenceNumber(workflowId) + 1;
        WorkflowStepEntity step = stepRepository.save(new WorkflowStepEntity(workflowId, sequence, from));
        events.publishEvent(new WorkflowStepEvent(step));
        return step;
    }

    @Transactional
    public void completeStep(WorkflowStepEntity step, WorkflowStatus to, boolean successful, String detail, String error) {
        step.complete(to, successful, detail, error);
        events.publishEvent(new WorkflowStepEvent(stepRepository.save(step)));
    }

    /** Records a human-driven state change that did not execute an engine step. */
    @Transactional
    public void recordTransition(UUID workflowId, WorkflowStatus from, WorkflowStatus to, String detail) {
        WorkflowStepEntity step = beginStep(workflowId, from);
        completeStep(step, to, true, detail, null);
    }
}
