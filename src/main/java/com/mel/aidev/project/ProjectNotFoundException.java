package com.mel.aidev.project;

import java.util.UUID;

/** Raised when a project id does not exist. Mapped to 404. */
public class ProjectNotFoundException extends RuntimeException {

    public ProjectNotFoundException(UUID projectId) {
        super("Project " + projectId + " not found");
    }

    public ProjectNotFoundException(String message) {
        super(message);
    }
}
