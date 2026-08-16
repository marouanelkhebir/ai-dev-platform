package com.company.aidev.gitlab.model;

import java.util.Locale;

/** Status of a GitLab pipeline or job. */
public enum PipelineStatus {
    CREATED,
    WAITING_FOR_RESOURCE,
    PREPARING,
    PENDING,
    RUNNING,
    SUCCESS,
    FAILED,
    CANCELED,
    SKIPPED,
    MANUAL,
    SCHEDULED,
    UNKNOWN;

    public static PipelineStatus from(String raw) {
        if (raw == null || raw.isBlank()) {
            return UNKNOWN;
        }
        try {
            return valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return UNKNOWN;
        }
    }

    public boolean isFinished() {
        return this == SUCCESS || this == FAILED || this == CANCELED || this == SKIPPED;
    }

    public boolean isSuccessful() {
        // A skipped pipeline means no CI ran on this branch. Treating it as a pass would silently
        // remove the CI gate, so it does not count as successful.
        return this == SUCCESS;
    }
}
