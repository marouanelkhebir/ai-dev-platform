package com.mel.aidev.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.mel.aidev.domain.AcceptanceCriterionResult;
import com.mel.aidev.domain.AcceptanceReport;
import com.mel.aidev.domain.AcceptanceStatus;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Models drop, merge and invent acceptance criteria. Silently accepting that is how a ticket reaches
 * "4/4 covered" with a criterion nobody looked at, so the report is realigned on the ticket.
 */
class AcceptanceAgentAlignmentTest {

    private final AcceptanceAgent agent = new AcceptanceAgent(null, null);

    @Test
    @DisplayName("adds a NOT_VERIFIABLE result for a criterion the model skipped")
    void shouldFillMissingCriteria() {
        List<String> ticketCriteria = List.of("Fee is suspended", "Customer is notified", "Audit entry is written");
        AcceptanceReport modelReport = new AcceptanceReport(
                List.of(new AcceptanceCriterionResult(
                        "Fee is suspended", AcceptanceStatus.PASS, List.of("FeeTest#suspend"), null)),
                "only looked at the first one");

        AcceptanceReport aligned = agent.alignWithTicket(ticketCriteria, modelReport);

        assertThat(aligned.totalCriteria()).isEqualTo(3);
        assertThat(aligned.passedCriteria()).isEqualTo(1);
        assertThat(aligned.results().get(1).status()).isEqualTo(AcceptanceStatus.NOT_VERIFIABLE);
        assertThat(aligned.results().get(2).comment()).contains("did not report");
        assertThat(aligned.fullyCovered()).isFalse();
    }

    @Test
    @DisplayName("drops a criterion the model invented")
    void shouldDropInventedCriteria() {
        List<String> ticketCriteria = List.of("Fee is suspended");
        AcceptanceReport modelReport = new AcceptanceReport(
                List.of(
                        new AcceptanceCriterionResult(
                                "Fee is suspended", AcceptanceStatus.PASS, List.of("FeeTest#suspend"), null),
                        new AcceptanceCriterionResult(
                                "Performance stays under 200ms", AcceptanceStatus.PASS, List.of("PerfTest"), null)),
                null);

        AcceptanceReport aligned = agent.alignWithTicket(ticketCriteria, modelReport);

        assertThat(aligned.totalCriteria()).isEqualTo(1);
        assertThat(aligned.results().get(0).criterion()).isEqualTo("Fee is suspended");
    }

    @Test
    @DisplayName("matches criteria whose whitespace or case drifted")
    void shouldMatchDespiteFormattingDrift() {
        List<String> ticketCriteria = List.of("The active fee must be suspended");
        AcceptanceReport modelReport = new AcceptanceReport(
                List.of(new AcceptanceCriterionResult(
                        "the   active fee   must be SUSPENDED", AcceptanceStatus.PASS, List.of("FeeTest"), "ok")),
                null);

        AcceptanceReport aligned = agent.alignWithTicket(ticketCriteria, modelReport);

        assertThat(aligned.passedCriteria()).isEqualTo(1);
        // The ticket wording wins, so the report is comparable with Jira.
        assertThat(aligned.results().get(0).criterion()).isEqualTo("The active fee must be suspended");
    }

    @Test
    @DisplayName("leaves the report untouched when the ticket has no criteria")
    void shouldPassThroughWithoutTicketCriteria() {
        AcceptanceReport modelReport = new AcceptanceReport(List.of(), "nothing to check");

        assertThat(agent.alignWithTicket(List.of(), modelReport)).isSameAs(modelReport);
    }
}
