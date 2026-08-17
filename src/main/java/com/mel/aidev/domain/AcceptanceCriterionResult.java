package com.mel.aidev.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * Verification of one acceptance criterion.
 *
 * <p>{@code evidence} must point at something real (a test name, a Cucumber scenario, a diff hunk).
 * A criterion without evidence is never {@link AcceptanceStatus#PASS}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AcceptanceCriterionResult(
        String criterion, AcceptanceStatus status, List<String> evidence, String comment) {

    public AcceptanceCriterionResult {
        status = status == null ? AcceptanceStatus.FAIL : status;
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
        if (status == AcceptanceStatus.PASS && evidence.isEmpty()) {
            status = AcceptanceStatus.NOT_VERIFIABLE;
        }
    }

    public boolean passed() {
        return status == AcceptanceStatus.PASS;
    }
}
