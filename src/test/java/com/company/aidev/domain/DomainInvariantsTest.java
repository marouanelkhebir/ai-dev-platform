package com.company.aidev.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The domain records enforce the rules that must not depend on a prompt being followed. A model that
 * approves a change while reporting a blocker, or marks a criterion as passing with no evidence, is
 * corrected here rather than trusted.
 */
class DomainInvariantsTest {

    @Nested
    @DisplayName("CodeReview")
    class CodeReviewTest {

        @Test
        @DisplayName("an approval containing a blocking finding becomes a change request")
        void shouldOverrideContradictoryApproval() {
            CodeReview review = new CodeReview(
                    ReviewDecision.APPROVE,
                    List.of(new ReviewFinding(Severity.BLOCKER, "Fee.java", 10, "correctness", "NPE", "guard it")),
                    "looks fine to me");

            assertThat(review.normalized().decision()).isEqualTo(ReviewDecision.REQUEST_CHANGES);
        }

        @Test
        @DisplayName("an approval with only minor findings stays an approval")
        void shouldKeepApprovalWithMinorFindings() {
            CodeReview review = new CodeReview(
                    ReviewDecision.APPROVE,
                    List.of(new ReviewFinding(Severity.MINOR, "Fee.java", 10, "readability", "naming", "rename")),
                    "fine");

            assertThat(review.normalized().decision()).isEqualTo(ReviewDecision.APPROVE);
        }

        @Test
        @DisplayName("a missing decision is treated as a change request")
        void shouldDefaultToRequestChanges() {
            assertThat(new CodeReview(null, List.of(), null).decision()).isEqualTo(ReviewDecision.REQUEST_CHANGES);
        }
    }

    @Nested
    @DisplayName("SecurityReport")
    class SecurityReportTest {

        @Test
        @DisplayName("a confirmed critical finding blocks the approval")
        void shouldBlockOnConfirmedCritical() {
            SecurityReport report = new SecurityReport(
                    ReviewDecision.APPROVE,
                    List.of(new SecurityFinding(
                            Severity.CRITICAL, "sql-injection", "Repo.java", 1, "concat", "bind it", "sast", false)),
                    "ok");

            assertThat(report.normalized().decision()).isEqualTo(ReviewDecision.REQUEST_CHANGES);
        }

        @Test
        @DisplayName("a finding marked as a false positive does not block")
        void shouldNotBlockOnFalsePositive() {
            SecurityReport report = new SecurityReport(
                    ReviewDecision.APPROVE,
                    List.of(new SecurityFinding(
                            Severity.CRITICAL, "sql-injection", "Test.java", 1, "test only", "none", "sast", true)),
                    "ok");

            assertThat(report.normalized().decision()).isEqualTo(ReviewDecision.APPROVE);
        }
    }

    @Nested
    @DisplayName("AcceptanceReport")
    class AcceptanceReportTest {

        @Test
        @DisplayName("a criterion claimed as passing with no evidence is downgraded")
        void shouldDowngradePassWithoutEvidence() {
            AcceptanceCriterionResult result =
                    new AcceptanceCriterionResult("AC1", AcceptanceStatus.PASS, List.of(), "trust me");

            assertThat(result.status()).isEqualTo(AcceptanceStatus.NOT_VERIFIABLE);
            assertThat(result.passed()).isFalse();
        }

        @Test
        @DisplayName("full coverage requires every criterion to pass")
        void shouldRequireFullCoverage() {
            AcceptanceReport report = new AcceptanceReport(
                    List.of(
                            new AcceptanceCriterionResult("AC1", AcceptanceStatus.PASS, List.of("TestA#case"), null),
                            new AcceptanceCriterionResult("AC2", AcceptanceStatus.PARTIAL, List.of("TestB"), null)),
                    "summary");

            assertThat(report.totalCriteria()).isEqualTo(2);
            assertThat(report.passedCriteria()).isEqualTo(1);
            assertThat(report.fullyCovered()).isFalse();
        }

        @Test
        @DisplayName("an empty report is never fully covered")
        void shouldNotConsiderEmptyReportCovered() {
            assertThat(new AcceptanceReport(List.of(), null).fullyCovered()).isFalse();
        }
    }

    @Nested
    @DisplayName("TicketAnalysis")
    class TicketAnalysisTest {

        @Test
        @DisplayName("ambiguities block automation")
        void shouldBlockOnAmbiguities() {
            TicketAnalysis analysis = new TicketAnalysis(
                    "BANK-1", "obj", List.of("AC1"), List.of(), List.of("what threshold?"), RiskLevel.LOW, null);

            assertThat(analysis.blocksAutomation()).isTrue();
        }

        @Test
        @DisplayName("a ticket with no acceptance criteria blocks automation")
        void shouldBlockWithoutAcceptanceCriteria() {
            TicketAnalysis analysis =
                    new TicketAnalysis("BANK-1", "obj", List.of(), List.of(), List.of(), RiskLevel.LOW, null);

            assertThat(analysis.blocksAutomation()).isTrue();
        }

        @Test
        @DisplayName("a complete ticket does not block")
        void shouldNotBlockWhenComplete() {
            TicketAnalysis analysis =
                    new TicketAnalysis("BANK-1", "obj", List.of("AC1"), List.of(), List.of(), RiskLevel.LOW, null);

            assertThat(analysis.blocksAutomation()).isFalse();
        }
    }
}
