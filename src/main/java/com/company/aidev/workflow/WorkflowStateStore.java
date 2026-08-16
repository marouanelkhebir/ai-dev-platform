package com.company.aidev.workflow;

import com.company.aidev.config.WorkflowProperties;
import com.company.aidev.persistence.entity.WorkflowEntity;
import com.company.aidev.persistence.entity.WorkflowStepEntity;
import com.company.aidev.persistence.repository.WorkflowRepository;
import com.company.aidev.persistence.repository.WorkflowStepRepository;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    public WorkflowStateStore(
            WorkflowRepository workflowRepository,
            WorkflowStepRepository stepRepository,
            WorkflowProperties properties) {
        this.workflowRepository = workflowRepository;
        this.stepRepository = stepRepository;
        this.properties = properties;
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
        return workflowRepository.saveAndFlush(workflow);
    }

    @Transactional
    public Optional<WorkflowEntity> find(UUID workflowId) {
        return workflowRepository.findById(workflowId);
    }

    @Transactional
    public WorkflowStepEntity beginStep(UUID workflowId, WorkflowStatus from) {
        int sequence = stepRepository.findMaxSequenceNumber(workflowId) + 1;
        return stepRepository.save(new WorkflowStepEntity(workflowId, sequence, from));
    }

    @Transactional
    public void completeStep(WorkflowStepEntity step, WorkflowStatus to, boolean successful, String detail, String error) {
        step.complete(to, successful, detail, error);
        stepRepository.save(step);
    }
}
