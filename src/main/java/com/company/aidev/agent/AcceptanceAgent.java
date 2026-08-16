package com.company.aidev.agent;

import com.company.aidev.domain.AcceptanceCriterionResult;
import com.company.aidev.domain.AcceptanceReport;
import com.company.aidev.domain.AcceptanceStatus;
import com.company.aidev.domain.TestReport;
import com.company.aidev.domain.TicketAnalysis;
import com.company.aidev.llm.PromptLoader;
import com.company.aidev.persistence.entity.AgentExecutionEntity;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Checks that every acceptance criterion of the ticket has evidence in the change.
 *
 * <p>This is the last automated gate before a human looks at the merge request, and the one that
 * decides whether the work is finished. The agent must point at a concrete artefact — a test name, a
 * Cucumber scenario, a diff hunk — for each criterion; a criterion with no evidence can never be
 * reported as passing, which is enforced in {@link AcceptanceCriterionResult} rather than trusted to
 * the prompt.
 */
@Component
public class AcceptanceAgent {

    private static final Logger log = LoggerFactory.getLogger(AcceptanceAgent.class);

    private final AgentSupport agentSupport;
    private final PromptLoader promptLoader;

    public AcceptanceAgent(AgentSupport agentSupport, PromptLoader promptLoader) {
        this.agentSupport = agentSupport;
        this.promptLoader = promptLoader;
    }

    public AcceptanceReport verify(
            UUID workflowId, int attempt, TicketAnalysis analysis, String diff, TestReport testReport) {

        AgentExecutionEntity execution = agentSupport.beginExecution(AgentType.ACCEPTANCE, workflowId, attempt);

        String systemPrompt = promptLoader.load("acceptance");
        String userPrompt = """
                # Ticket %s
                Objective: %s

                ## Acceptance criteria to verify, one by one
                %s

                ## Test results
                %d test(s) executed, %d failing.
                %s

                ## Diff of the change
                ```diff
                %s
                ```

                Answer with the JSON object described in your instructions.
                """
                .formatted(
                        analysis.ticketId(),
                        analysis.objective(),
                        numbered(analysis.acceptanceCriteria()),
                        testReport.totalTests(),
                        testReport.failedTests(),
                        testReport.missingTestCases().isEmpty()
                                ? ""
                                : "Coverage gaps reported by the test agent:\n"
                                        + String.join("\n", testReport.missingTestCases()),
                        diff.isBlank() ? "(empty diff)" : diff);

        AgentRequest request =
                AgentRequest.withoutTools(AgentType.ACCEPTANCE, workflowId, attempt, systemPrompt, userPrompt);

        AcceptanceReport report = agentSupport.execute(request, execution, AcceptanceReport.class);
        AcceptanceReport aligned = alignWithTicket(analysis.acceptanceCriteria(), report);

        log.info(
                "Acceptance for {} attempt {}: {}/{} criteria covered",
                analysis.ticketId(),
                attempt,
                aligned.passedCriteria(),
                aligned.totalCriteria());
        return aligned;
    }

    /**
     * Guarantees one result per ticket criterion.
     *
     * <p>Models drop criteria, merge two into one, or invent an extra one. Silently accepting that
     * would let a ticket reach "4/4 covered" while a criterion was never examined, so the report is
     * rebuilt from the ticket's own list.
     */
    AcceptanceReport alignWithTicket(List<String> ticketCriteria, AcceptanceReport report) {
        if (ticketCriteria.isEmpty()) {
            return report;
        }
        Map<String, AcceptanceCriterionResult> byCriterion = new LinkedHashMap<>();
        for (AcceptanceCriterionResult result : report.results()) {
            byCriterion.put(normalize(result.criterion()), result);
        }

        List<AcceptanceCriterionResult> aligned = new ArrayList<>();
        for (String criterion : ticketCriteria) {
            AcceptanceCriterionResult match = byCriterion.get(normalize(criterion));
            if (match == null) {
                match = new AcceptanceCriterionResult(
                        criterion,
                        AcceptanceStatus.NOT_VERIFIABLE,
                        List.of(),
                        "The acceptance agent did not report on this criterion.");
            } else {
                match = new AcceptanceCriterionResult(criterion, match.status(), match.evidence(), match.comment());
            }
            aligned.add(match);
        }
        return new AcceptanceReport(aligned, report.summary());
    }

    private static String normalize(String value) {
        return value == null ? "" : value.toLowerCase(java.util.Locale.ROOT).replaceAll("\\s+", " ").trim();
    }

    private static String numbered(List<String> items) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < items.size(); i++) {
            sb.append("AC").append(i + 1).append(": ").append(items.get(i)).append('\n');
        }
        return sb.isEmpty() ? "(none)" : sb.toString();
    }
}
