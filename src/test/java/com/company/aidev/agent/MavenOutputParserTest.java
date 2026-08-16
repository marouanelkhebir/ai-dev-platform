package com.company.aidev.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.aidev.domain.TestReport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Test counts are facts and must come from the build log, never from a model. These tests use real
 * Surefire output shapes.
 */
class MavenOutputParserTest {

    private final MavenOutputParser parser = new MavenOutputParser();

    @Test
    @DisplayName("reads the totals of a green build")
    void shouldParseSuccessfulBuild() {
        String output =
                """
                [INFO] -------------------------------------------------------
                [INFO]  T E S T S
                [INFO] -------------------------------------------------------
                [INFO] Running com.company.fee.FeeSuspensionServiceTest
                [INFO] Tests run: 12, Failures: 0, Errors: 0, Skipped: 1, Time elapsed: 0.42 s
                [INFO] Results:
                [INFO] Tests run: 12, Failures: 0, Errors: 0, Skipped: 1
                [INFO] BUILD SUCCESS
                """;

        TestReport report = parser.parse(output, true);

        assertThat(report.successful()).isTrue();
        assertThat(report.totalTests()).isEqualTo(12);
        assertThat(report.failedTests()).isZero();
        assertThat(report.skippedTests()).isEqualTo(1);
        assertThat(report.failures()).isEmpty();
    }

    @Test
    @DisplayName("extracts the failing tests of a red build")
    void shouldParseFailingBuild() {
        String output =
                """
                [INFO] Results:
                [ERROR] Failures:
                [ERROR]   FeeSuspensionServiceTest.shouldSuspendFee:42 expected: <SUSPENDED> but was: <ACTIVE>
                [ERROR]   FeeSuspensionServiceTest.shouldRefund:88 expected: <10.00> but was: <0.00>
                [ERROR] Tests run: 12, Failures: 2, Errors: 0, Skipped: 0
                [INFO] BUILD FAILURE
                """;

        TestReport report = parser.parse(output, false);

        assertThat(report.successful()).isFalse();
        assertThat(report.totalTests()).isEqualTo(12);
        assertThat(report.failedTests()).isEqualTo(2);
        assertThat(report.failures()).hasSize(2);
        assertThat(report.failures().get(0).displayName()).isEqualTo("FeeSuspensionServiceTest#shouldSuspendFee");
        assertThat(report.failures().get(0).message()).contains("expected: <SUSPENDED>");
    }

    @Test
    @DisplayName("counts errors as failures")
    void shouldCountErrorsAsFailures() {
        String output = "[ERROR] Tests run: 5, Failures: 1, Errors: 2, Skipped: 0\n[INFO] BUILD FAILURE";

        TestReport report = parser.parse(output, false);

        assertThat(report.failedTests()).isEqualTo(3);
        assertThat(report.successful()).isFalse();
    }

    @Test
    @DisplayName("reports a compilation failure as a build failure rather than zero tests passing")
    void shouldDetectCompilationFailure() {
        String output =
                """
                [ERROR] /src/main/java/com/company/fee/FeeService.java:[42,15] cannot find symbol
                [ERROR]   symbol:   method suspend()
                [INFO] BUILD FAILURE
                """;

        TestReport report = parser.parse(output, false);

        assertThat(report.successful()).isFalse();
        assertThat(report.failures()).hasSize(1);
        assertThat(report.failures().get(0).message()).contains("Compilation failed");
    }

    @Test
    @DisplayName("a build that exits non-zero is never reported as successful")
    void shouldNeverReportSuccessWhenMavenFailed() {
        String output = "[INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 0\n[INFO] BUILD FAILURE";

        // Maven can fail after the tests, for instance on Checkstyle or Enforcer. Reporting this as
        // green is exactly how a broken build reaches a merge request.
        assertThat(parser.parse(output, false).successful()).isFalse();
    }

    @Test
    @DisplayName("renders compact feedback for the developer agent")
    void shouldRenderFeedback() {
        String output =
                """
                [ERROR]   FeeServiceTest.shouldSuspend:42 expected: <A> but was: <B>
                [ERROR] Tests run: 4, Failures: 1, Errors: 0, Skipped: 0
                """;

        String feedback = parser.parse(output, false).toFeedback();

        assertThat(feedback).contains("3/4 passed", "FeeServiceTest#shouldSuspend");
    }

    @Test
    @DisplayName("includes the Maven diagnostic when the build fails before tests")
    void shouldIncludeBuildOutputInFeedbackForPreTestFailure() {
        String output = "[ERROR] Non-resolvable parent POM for com.example:app:1.0";

        String feedback = parser.parse(output, false).toFeedback();

        assertThat(feedback).contains(
                "- build:",
                "Maven build failed",
                "Build output:",
                "Non-resolvable parent POM");
    }
}
