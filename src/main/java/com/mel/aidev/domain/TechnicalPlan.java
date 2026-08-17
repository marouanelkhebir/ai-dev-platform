package com.mel.aidev.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * Implementation plan produced by the architect agent.
 *
 * <p>The architect is read-only: it describes what should change, it never changes anything.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TechnicalPlan(
        List<FileChange> filesToModify,
        List<String> implementationSteps,
        List<String> testsToAdd,
        List<String> technicalRisks,
        String architectureNotes) {

    public TechnicalPlan {
        filesToModify = filesToModify == null ? List.of() : List.copyOf(filesToModify);
        implementationSteps = implementationSteps == null ? List.of() : List.copyOf(implementationSteps);
        testsToAdd = testsToAdd == null ? List.of() : List.copyOf(testsToAdd);
        technicalRisks = technicalRisks == null ? List.of() : List.copyOf(technicalRisks);
    }

    public boolean isEmpty() {
        return filesToModify.isEmpty() && implementationSteps.isEmpty();
    }
}
