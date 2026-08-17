package com.mel.aidev.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * Structured reading of a Jira ticket produced by the Jira analyst agent.
 *
 * <p>The agent is not allowed to invent business rules. Acceptance criteria can be taken verbatim
 * from the ticket or derived as directly observable checks of an explicit request. Anything that
 * cannot be established goes into {@link #ambiguities()}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TicketAnalysis(
        String ticketId,
        String objective,
        List<String> acceptanceCriteria,
        List<String> impactedServices,
        List<String> ambiguities,
        RiskLevel riskLevel,
        String summaryForDeveloper) {

    public TicketAnalysis {
        acceptanceCriteria = acceptanceCriteria == null ? List.of() : List.copyOf(acceptanceCriteria);
        impactedServices = impactedServices == null ? List.of() : List.copyOf(impactedServices);
        ambiguities = ambiguities == null ? List.of() : List.copyOf(ambiguities);
        riskLevel = riskLevel == null ? RiskLevel.MEDIUM : riskLevel;
    }

    /** Only unresolved business ambiguity blocks automation. */
    public boolean blocksAutomation() {
        return !ambiguities.isEmpty();
    }
}
