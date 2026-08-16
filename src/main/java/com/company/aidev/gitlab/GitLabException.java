package com.company.aidev.gitlab;

/** Raised when a GitLab API call fails. */
public class GitLabException extends RuntimeException {

    public GitLabException(String message) {
        super(message);
    }

    public GitLabException(String message, Throwable cause) {
        super(message, cause);
    }
}
