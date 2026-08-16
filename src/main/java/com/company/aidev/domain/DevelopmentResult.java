package com.company.aidev.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/** Self-report of the developer agent at the end of one implementation attempt. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DevelopmentResult(
        boolean completed, List<String> changedFiles, String summary, List<String> remainingWork) {

    public DevelopmentResult {
        changedFiles = changedFiles == null ? List.of() : List.copyOf(changedFiles);
        remainingWork = remainingWork == null ? List.of() : List.copyOf(remainingWork);
    }
}
