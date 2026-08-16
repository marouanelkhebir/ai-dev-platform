package com.company.aidev.workflow;

import com.company.aidev.domain.RepositoryContext;
import com.company.aidev.domain.RepositoryRules;
import com.company.aidev.persistence.entity.WorkflowEntity;
import com.company.aidev.sandbox.Sandbox;

/**
 * Mutable state of one engine run.
 *
 * <p>A run executes as many steps as it can before the workflow has to wait for an external event.
 * The sandbox lives for the duration of the run only, which is what keeps a container from
 * surviving a crash: on restart, the workflow simply re-enters {@code DEVELOPING} with a fresh one.
 */
class WorkflowRun {

    private WorkflowEntity workflow;
    private Sandbox sandbox;
    private RepositoryRules rules;
    private RepositoryContext repositoryContext;

    WorkflowRun(WorkflowEntity workflow) {
        this.workflow = workflow;
    }

    WorkflowEntity workflow() {
        return workflow;
    }

    /**
     * Replaces the entity with the instance returned by the persistence layer.
     *
     * <p>Saving a detached entity returns a managed copy with an incremented version; keeping the
     * stale instance would make the next save fail with an optimistic locking error.
     */
    void workflow(WorkflowEntity saved) {
        this.workflow = saved;
    }

    Sandbox sandbox() {
        return sandbox;
    }

    void sandbox(Sandbox sandbox) {
        this.sandbox = sandbox;
    }

    boolean hasSandbox() {
        return sandbox != null;
    }

    RepositoryRules rules() {
        return rules == null ? RepositoryRules.empty() : rules;
    }

    void rules(RepositoryRules rules) {
        this.rules = rules;
    }

    RepositoryContext repositoryContext() {
        return repositoryContext;
    }

    void repositoryContext(RepositoryContext repositoryContext) {
        this.repositoryContext = repositoryContext;
    }
}
