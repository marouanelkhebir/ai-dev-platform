package com.company.aidev.agent;

import com.company.aidev.domain.CodeReview;
import com.company.aidev.domain.RepositoryRules;
import com.company.aidev.domain.TicketAnalysis;
import com.company.aidev.llm.PromptLoader;
import com.company.aidev.persistence.entity.AgentExecutionEntity;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Independent review of the change.
 *
 * <p>Independence is structural, not a matter of prompting: this agent gets no tools, no sandbox and
 * no memory of the developer's reasoning. It sees the ticket, the acceptance criteria, the diff and
 * the repository rules — exactly what a human reviewer opening the merge request would see.
 */
@Component
public class ReviewerAgent {

    private static final Logger log = LoggerFactory.getLogger(ReviewerAgent.class);

    private final AgentSupport agentSupport;
    private final PromptLoader promptLoader;

    public ReviewerAgent(AgentSupport agentSupport, PromptLoader promptLoader) {
        this.agentSupport = agentSupport;
        this.promptLoader = promptLoader;
    }

    public CodeReview review(
            UUID workflowId, int attempt, TicketAnalysis analysis, String diff, RepositoryRules rules) {

        AgentExecutionEntity execution = agentSupport.beginExecution(AgentType.REVIEWER, workflowId, attempt);

        String systemPrompt = promptLoader.load("reviewer");
        String userPrompt = """
                # Ticket %s
                Objective: %s

                ## Acceptance criteria
                %s

                ## Architecture and development rules of this repository
                %s

                ## Diff to review
                ```diff
                %s
                ```

                Review this change. Answer with the JSON object described in your instructions.
                """
                .formatted(
                        analysis.ticketId(),
                        analysis.objective(),
                        numbered(analysis.acceptanceCriteria()),
                        rulesSection(rules),
                        diff.isBlank() ? "(empty diff)" : diff);

        AgentRequest request =
                AgentRequest.withoutTools(AgentType.REVIEWER, workflowId, attempt, systemPrompt, userPrompt);

        CodeReview review = agentSupport.execute(request, execution, CodeReview.class).normalized();
        log.info(
                "Code review for {} attempt {}: {} with {} finding(s)",
                analysis.ticketId(),
                attempt,
                review.decision(),
                review.findings().size());
        return review;
    }

    private static String rulesSection(RepositoryRules rules) {
        String rendered = rules.render(
                RepositoryRules.ARCHITECTURE,
                RepositoryRules.CODING_GUIDELINES,
                RepositoryRules.DOMAIN,
                RepositoryRules.TESTING_GUIDELINES);
        return rendered.isBlank() ? "(the repository does not define .ai rules; apply standard Spring Boot practices)" : rendered;
    }

    private static String numbered(List<String> items) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < items.size(); i++) {
            sb.append("AC").append(i + 1).append(": ").append(items.get(i)).append('\n');
        }
        return sb.isEmpty() ? "(none)" : sb.toString();
    }
}
