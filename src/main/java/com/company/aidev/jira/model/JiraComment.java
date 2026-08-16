package com.company.aidev.jira.model;

import java.time.Instant;

/** A comment on a Jira issue. */
public record JiraComment(String id, String author, String body, Instant created) {}
