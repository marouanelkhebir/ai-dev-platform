package com.company.aidev.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/** Output of the reviewer agent. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CodeReview(ReviewDecision decision, List<ReviewFinding> findings, String summary) {

    public CodeReview {
        decision = decision == null ? ReviewDecision.REQUEST_CHANGES : decision;
        findings = findings == null ? List.of() : List.copyOf(findings);
    }

    /**
     * A reviewer that approves while reporting blocking findings is contradicting itself; the
     * blocking findings win.
     */
    public CodeReview normalized() {
        boolean blocking = findings.stream().anyMatch(f -> f.severity().isBlocking());
        if (blocking && decision == ReviewDecision.APPROVE) {
            return new CodeReview(ReviewDecision.REQUEST_CHANGES, findings, summary);
        }
        return this;
    }

    public boolean approved() {
        return decision == ReviewDecision.APPROVE;
    }

    public String toFeedback() {
        StringBuilder sb = new StringBuilder("Code review requested changes.\n");
        if (summary != null && !summary.isBlank()) {
            sb.append(summary).append('\n');
        }
        findings.stream()
                .filter(f -> f.severity().ordinal() >= Severity.MAJOR.ordinal())
                .forEach(f -> sb.append(f.toMarkdown()).append('\n'));
        return sb.toString();
    }
}
