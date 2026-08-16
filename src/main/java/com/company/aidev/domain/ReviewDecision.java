package com.company.aidev.domain;

/** Verdict of the reviewer agent. Merging is never part of the decision space. */
public enum ReviewDecision {
    APPROVE,
    REQUEST_CHANGES
}
