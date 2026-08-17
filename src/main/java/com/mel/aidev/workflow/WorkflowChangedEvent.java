package com.mel.aidev.workflow;

import com.mel.aidev.persistence.entity.WorkflowEntity;

/**
 * Published after a workflow row was written.
 *
 * <p>Carries the saved entity rather than its identifier: the listeners run after the transaction
 * commits, and re-reading the row from another thread would race with the next step.
 */
public record WorkflowChangedEvent(WorkflowEntity workflow) {}
