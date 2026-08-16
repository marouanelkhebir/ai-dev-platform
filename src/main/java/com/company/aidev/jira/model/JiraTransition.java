package com.company.aidev.jira.model;

/** An available workflow transition of a Jira issue. */
public record JiraTransition(String id, String name, String targetStatusName) {}
