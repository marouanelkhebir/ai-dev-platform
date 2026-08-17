package com.mel.aidev.domain;

/** Overall risk carried by a ticket, as estimated by the Jira analyst agent. */
public enum RiskLevel {
    LOW,
    MEDIUM,
    HIGH,
    CRITICAL;

    public boolean atLeast(RiskLevel other) {
        return ordinal() >= other.ordinal();
    }
}
