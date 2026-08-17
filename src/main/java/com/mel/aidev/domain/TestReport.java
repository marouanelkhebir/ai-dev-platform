package com.mel.aidev.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/** Outcome of the local build run plus the test agent's gap analysis. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TestReport(
        boolean successful,
        int totalTests,
        int failedTests,
        int skippedTests,
        List<TestFailure> failures,
        List<String> missingTestCases,
        String rawOutputExcerpt) {

    public TestReport {
        failures = failures == null ? List.of() : List.copyOf(failures);
        missingTestCases = missingTestCases == null ? List.of() : List.copyOf(missingTestCases);
    }

    public static TestReport failed(String output, String reason) {
        return new TestReport(
                false,
                0,
                0,
                0,
                List.of(new TestFailure("build", null, reason, null)),
                List.of(),
                output);
    }

    /** Compact, token-friendly rendering handed back to the developer agent on retry. */
    public String toFeedback() {
        StringBuilder sb = new StringBuilder();
        sb.append("Tests: ")
                .append(totalTests - failedTests)
                .append('/')
                .append(totalTests)
                .append(" passed, ")
                .append(failedTests)
                .append(" failed.\n");
        for (TestFailure failure : failures) {
            sb.append("- ").append(failure.displayName()).append(": ").append(failure.message()).append('\n');
            if (failure.stackTraceExcerpt() != null && !failure.stackTraceExcerpt().isBlank()) {
                sb.append("  ").append(failure.stackTraceExcerpt()).append('\n');
            }
        }
        if (!missingTestCases.isEmpty()) {
            sb.append("Missing coverage:\n");
            missingTestCases.forEach(c -> sb.append("- ").append(c).append('\n'));
        }
        if (!successful && rawOutputExcerpt != null && !rawOutputExcerpt.isBlank()) {
            sb.append("Build output:\n").append(rawOutputExcerpt).append('\n');
        }
        return sb.toString();
    }
}
