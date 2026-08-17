package com.mel.aidev.domain;

/** Severity of a review or security finding. */
public enum Severity {
    INFO,
    MINOR,
    MAJOR,
    CRITICAL,
    BLOCKER;

    /** Findings at or above this level must block the merge request. */
    public boolean isBlocking() {
        return this == CRITICAL || this == BLOCKER;
    }
}
