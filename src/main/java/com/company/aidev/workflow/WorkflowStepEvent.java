package com.company.aidev.workflow;

import com.company.aidev.persistence.entity.WorkflowStepEntity;

/**
 * Published when a transition of the state machine started or finished.
 *
 * <p>The console draws its pipeline and its step durations from these, so both ends of a step are
 * announced: the opening tells the operator what is running now, the closing gives it a duration.
 */
public record WorkflowStepEvent(WorkflowStepEntity step) {}
