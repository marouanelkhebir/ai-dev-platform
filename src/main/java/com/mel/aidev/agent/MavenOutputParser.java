package com.mel.aidev.agent;

import com.mel.aidev.domain.TestFailure;
import com.mel.aidev.domain.TestReport;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Deterministic extraction of test results from a Maven build log.
 *
 * <p>Test counts are facts, and facts must not be produced by a language model. Asking an LLM "how
 * many tests failed" is how a workflow ends up creating a merge request for a red build. The model
 * is only asked about the things it is actually good at: which cases are missing, and why a failure
 * happens.
 */
@Component
public class MavenOutputParser {

    /** Surefire and Failsafe summary line. */
    private static final Pattern RESULT_LINE = Pattern.compile(
            "Tests run:\\s*(\\d+),\\s*Failures:\\s*(\\d+),\\s*Errors:\\s*(\\d+),\\s*Skipped:\\s*(\\d+)");

    /**
     * Failure entry of the "[ERROR] Failures:" / "[ERROR] Errors:" block.
     *
     * <p>Maven indents these with two or three spaces depending on the plugin version, hence
     * {@code \s+} rather than a fixed count. The class part must be followed by {@code .} or
     * {@code #}, which is what keeps the summary line ("Tests run: 12, ...") from matching.
     */
    private static final Pattern FAILURE_ENTRY =
            Pattern.compile("^\\[ERROR]\\s+([\\w.$]+)[.#]([\\w$]+)(?::\\d+)?\\s*(.*)$");

    private static final Pattern BUILD_FAILURE = Pattern.compile("(?m)^\\[(?:ERROR|INFO)]\\s+BUILD FAILURE");
    private static final Pattern COMPILATION_ERROR = Pattern.compile("(?m)^\\[ERROR].*\\.java:\\[\\d+,\\d+].*$");

    private static final int MAX_EXCERPT_CHARS = 8_000;

    /**
     * Parses a Maven log.
     *
     * @param output the combined stdout/stderr of the build
     * @param buildSucceeded whether Maven exited with code 0
     */
    public TestReport parse(String output, boolean buildSucceeded) {
        String safeOutput = output == null ? "" : output;

        int total = 0;
        int failures = 0;
        int errors = 0;
        int skipped = 0;

        // Maven prints one summary per module plus a final aggregate; the last one is the aggregate
        // for a single-module build and the per-module sums are what a multi-module build reports.
        List<int[]> summaries = new ArrayList<>();
        Matcher matcher = RESULT_LINE.matcher(safeOutput);
        while (matcher.find()) {
            summaries.add(new int[] {
                Integer.parseInt(matcher.group(1)),
                Integer.parseInt(matcher.group(2)),
                Integer.parseInt(matcher.group(3)),
                Integer.parseInt(matcher.group(4))
            });
        }
        if (!summaries.isEmpty()) {
            int[] last = summaries.get(summaries.size() - 1);
            total = last[0];
            failures = last[1];
            errors = last[2];
            skipped = last[3];
        }

        List<TestFailure> testFailures = extractFailures(safeOutput);

        if (summaries.isEmpty() && !buildSucceeded) {
            String reason = COMPILATION_ERROR.matcher(safeOutput).find()
                    ? "Compilation failed, no test was executed"
                    : (BUILD_FAILURE.matcher(safeOutput).find()
                            ? "Maven build failed before the tests ran"
                            : "Maven build failed");
            return TestReport.failed(excerpt(safeOutput), reason);
        }

        int failedTests = failures + errors;
        boolean successful = buildSucceeded && failedTests == 0;
        return new TestReport(successful, total, failedTests, skipped, testFailures, List.of(), excerpt(safeOutput));
    }

    private List<TestFailure> extractFailures(String output) {
        List<TestFailure> failures = new ArrayList<>();
        String[] lines = output.split("\\R");
        for (int i = 0; i < lines.length; i++) {
            Matcher matcher = FAILURE_ENTRY.matcher(lines[i]);
            if (!matcher.matches()) {
                continue;
            }
            String message = matcher.group(3);
            StringBuilder stack = new StringBuilder();
            for (int j = i + 1; j < Math.min(i + 6, lines.length); j++) {
                String next = lines[j];
                if (FAILURE_ENTRY.matcher(next).matches() || !next.startsWith("[ERROR]")) {
                    break;
                }
                stack.append(next.trim()).append('\n');
            }
            failures.add(new TestFailure(
                    matcher.group(1),
                    matcher.group(2),
                    message.isBlank() ? "assertion failed" : message.trim(),
                    stack.isEmpty() ? null : stack.toString().trim()));
        }
        return failures;
    }

    /** Keeps the end of the log, where Maven reports what actually went wrong. */
    private static String excerpt(String output) {
        if (output.length() <= MAX_EXCERPT_CHARS) {
            return output;
        }
        return "...[log truncated]...\n" + output.substring(output.length() - MAX_EXCERPT_CHARS);
    }
}
