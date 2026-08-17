package com.mel.aidev.project;

import java.util.List;

/**
 * Raised when a project cannot be saved as described. Mapped to 422.
 *
 * <p>Rejecting at save time rather than at launch time is the whole point: an unreachable repository
 * or a command the sandbox would refuse must surface while a human is looking at the screen, not
 * forty minutes into a workflow.
 */
public class ProjectValidationException extends RuntimeException {

    private final transient List<String> details;

    public ProjectValidationException(String message) {
        this(message, List.of());
    }

    public ProjectValidationException(String message, List<String> details) {
        super(message);
        this.details = details == null ? List.of() : List.copyOf(details);
    }

    public List<String> details() {
        return details;
    }
}
