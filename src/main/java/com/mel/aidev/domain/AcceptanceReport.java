package com.mel.aidev.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/** Output of the acceptance agent: one line per Jira acceptance criterion. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AcceptanceReport(List<AcceptanceCriterionResult> results, String summary) {

    public AcceptanceReport {
        results = results == null ? List.of() : List.copyOf(results);
    }

    public int totalCriteria() {
        return results.size();
    }

    public int passedCriteria() {
        return (int) results.stream().filter(AcceptanceCriterionResult::passed).count();
    }

    /** A merge request is not ready until every criterion is covered. */
    public boolean fullyCovered() {
        return !results.isEmpty() && passedCriteria() == totalCriteria();
    }

    public String toFeedback() {
        StringBuilder sb = new StringBuilder("Acceptance criteria not fully covered ("
                + passedCriteria() + "/" + totalCriteria() + ").\n");
        results.stream()
                .filter(r -> !r.passed())
                .forEach(r -> sb.append("- [")
                        .append(r.status())
                        .append("] ")
                        .append(r.criterion())
                        .append(r.comment() == null ? "" : " — " + r.comment())
                        .append('\n'));
        return sb.toString();
    }
}
