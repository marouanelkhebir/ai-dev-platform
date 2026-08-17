package com.mel.aidev.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/** Output of the security agent. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SecurityReport(ReviewDecision decision, List<SecurityFinding> findings, String summary) {

    public SecurityReport {
        decision = decision == null ? ReviewDecision.REQUEST_CHANGES : decision;
        findings = findings == null ? List.of() : List.copyOf(findings);
    }

    public SecurityReport normalized() {
        boolean blocking =
                findings.stream().anyMatch(f -> !f.falsePositive() && f.severity().isBlocking());
        if (blocking && decision == ReviewDecision.APPROVE) {
            return new SecurityReport(ReviewDecision.REQUEST_CHANGES, findings, summary);
        }
        return this;
    }

    public boolean approved() {
        return decision == ReviewDecision.APPROVE;
    }

    public String toFeedback() {
        StringBuilder sb = new StringBuilder("Security review requested changes.\n");
        if (summary != null && !summary.isBlank()) {
            sb.append(summary).append('\n');
        }
        findings.stream()
                .filter(f -> !f.falsePositive() && f.severity().ordinal() >= Severity.MAJOR.ordinal())
                .forEach(f -> sb.append(f.toMarkdown()).append('\n'));
        return sb.toString();
    }
}
