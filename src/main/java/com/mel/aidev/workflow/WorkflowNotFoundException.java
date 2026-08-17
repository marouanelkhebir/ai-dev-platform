package com.mel.aidev.workflow;

import java.util.UUID;

/** Raised when an API call references a workflow that does not exist. */
public class WorkflowNotFoundException extends RuntimeException {

    public WorkflowNotFoundException(UUID workflowId) {
        super("Workflow not found: " + workflowId);
    }
}
