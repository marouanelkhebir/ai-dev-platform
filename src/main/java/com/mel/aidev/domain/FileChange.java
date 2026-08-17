package com.mel.aidev.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** A single file the architect agent expects the developer agent to touch. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record FileChange(String path, ChangeType changeType, String reason) {

    public FileChange {
        changeType = changeType == null ? ChangeType.MODIFY : changeType;
    }
}
