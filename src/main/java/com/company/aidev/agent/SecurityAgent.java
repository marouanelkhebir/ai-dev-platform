package com.company.aidev.agent;

import com.company.aidev.domain.RepositoryRules;
import com.company.aidev.domain.SecurityReport;
import com.company.aidev.domain.TicketAnalysis;
import com.company.aidev.gitlab.model.ScannerReport;
import com.company.aidev.llm.PromptLoader;
import com.company.aidev.persistence.entity.AgentExecutionEntity;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Security review of the change and interpretation of the scanner output.
 *
 * <p>The agent does not replace SAST, dependency scanning or secret detection: those run in the
 * GitLab pipeline and their reports are handed to it as evidence. Its job is the part scanners are
 * bad at — deciding whether a finding is reachable in this codebase, and spotting the authorisation
 * or injection mistakes that no rule set encodes.
 */
@Component
public class SecurityAgent {

    private static final Logger log = LoggerFactory.getLogger(SecurityAgent.class);

    private final AgentSupport agentSupport;
    private final PromptLoader promptLoader;

    public SecurityAgent(AgentSupport agentSupport, PromptLoader promptLoader) {
        this.agentSupport = agentSupport;
        this.promptLoader = promptLoader;
    }

    public SecurityReport review(
            UUID workflowId,
            int attempt,
            TicketAnalysis analysis,
            String diff,
            List<ScannerReport> scannerReports,
            RepositoryRules rules) {

        AgentExecutionEntity execution = agentSupport.beginExecution(AgentType.SECURITY, workflowId, attempt);

        String systemPrompt = promptLoader.load("security");
        String userPrompt = """
                # Ticket %s
                Objective: %s

                ## Security rules of this repository
                %s

                ## Diff to review
                ```diff
                %s
                ```

                ## Scanner reports from the GitLab pipeline
                %s

                Answer with the JSON object described in your instructions.
                """
                .formatted(
                        analysis.ticketId(),
                        analysis.objective(),
                        rules.render(RepositoryRules.SECURITY, RepositoryRules.ARCHITECTURE).isBlank()
                                ? "(none declared)"
                                : rules.render(RepositoryRules.SECURITY, RepositoryRules.ARCHITECTURE),
                        diff.isBlank() ? "(empty diff)" : diff,
                        renderScannerReports(scannerReports));

        AgentRequest request =
                AgentRequest.withoutTools(AgentType.SECURITY, workflowId, attempt, systemPrompt, userPrompt);

        SecurityReport report = agentSupport.execute(request, execution, SecurityReport.class).normalized();
        log.info(
                "Security review for {} attempt {}: {} with {} finding(s)",
                analysis.ticketId(),
                attempt,
                report.decision(),
                report.findings().size());
        return report;
    }

    private static String renderScannerReports(List<ScannerReport> reports) {
        if (reports == null || reports.isEmpty()) {
            return "(no scanner report available — say so in your summary and rely on the diff only; "
                    + "do not claim the change was scanned)";
        }
        StringBuilder sb = new StringBuilder();
        for (ScannerReport report : reports) {
            if (report.isEmpty()) {
                continue;
            }
            sb.append("### ")
                    .append(report.kind())
                    .append(" (job ")
                    .append(report.jobName())
                    .append(")\n```json\n")
                    .append(report.content())
                    .append("\n```\n");
        }
        return sb.isEmpty() ? "(scanner jobs produced empty reports)" : sb.toString();
    }
}
