package com.company.aidev.jira;

/** Raised when a Jira call fails in a way the workflow cannot recover from on its own. */
public class JiraException extends RuntimeException {

    public JiraException(String message) {
        super(message);
    }

    public JiraException(String message, Throwable cause) {
        super(message, cause);
    }
}
