package com.mel.aidev.agent;

import com.mel.aidev.config.AiProperties;
import com.mel.aidev.llm.LlmCallContext;
import com.mel.aidev.llm.LlmModelProvider;
import com.mel.aidev.llm.LlmOutputParseException;
import com.mel.aidev.llm.StructuredOutputParser;
import com.mel.aidev.observability.LogContext;
import com.mel.aidev.observability.PlatformMetrics;
import com.mel.aidev.persistence.entity.AgentExecutionEntity;
import com.mel.aidev.persistence.repository.AgentExecutionRepository;
import com.mel.aidev.security.SecretRedactor;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.Result;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Runs an agent and records everything about it.
 *
 * <p>Responsibilities kept here rather than duplicated in each agent: model selection, tool binding,
 * prompt persistence, structured output parsing with one repair attempt, metrics and MDC.
 */
@Component
public class AgentSupport {

    private static final Logger log = LoggerFactory.getLogger(AgentSupport.class);
    private static final int MAX_AUDIT_CHARS = 60_000;

    private static final String REPAIR_INSTRUCTION =
            """

            Your previous answer could not be parsed: %s

            Answer again with the JSON object only. No prose before it, no prose after it, no markdown
            code fence. Every field described in your instructions must be present.
            """;

    private final LlmModelProvider modelProvider;
    private final StructuredOutputParser parser;
    private final AgentExecutionRepository executionRepository;
    private final SecretRedactor redactor;
    private final PlatformMetrics metrics;
    private final AiProperties aiProperties;

    public AgentSupport(
            LlmModelProvider modelProvider,
            StructuredOutputParser parser,
            AgentExecutionRepository executionRepository,
            SecretRedactor redactor,
            PlatformMetrics metrics,
            AiProperties aiProperties) {
        this.modelProvider = modelProvider;
        this.parser = parser;
        this.executionRepository = executionRepository;
        this.redactor = redactor;
        this.metrics = metrics;
        this.aiProperties = aiProperties;
    }

    /**
     * Reserves an execution identifier before the agent runs, so that tool calls made during the run
     * can be attached to it.
     */
    public AgentExecutionEntity beginExecution(AgentType agent, UUID workflowId, int attempt) {
        AgentExecutionEntity execution =
                new AgentExecutionEntity(workflowId, agent, modelProvider.modelNameFor(agent), attempt);
        return executionRepository.save(execution);
    }

    /**
     * Executes an agent and deserialises its answer.
     *
     * @throws AgentExecutionException when the model fails or its answer cannot be parsed twice in a row
     */
    public <T> T execute(AgentRequest request, AgentExecutionEntity execution, Class<T> outputType) {
        String model = modelProvider.modelNameFor(request.agent());
        execution.setPrompts(
                redactor.redactAndTruncate(request.systemPrompt(), MAX_AUDIT_CHARS),
                redactor.redactAndTruncate(request.userPrompt(), MAX_AUDIT_CHARS));

        try (var ignored = LogContext.of()
                        .workflow(request.workflowId())
                        .agent(request.agent().name())
                        .model(model)
                        .attempt(request.attempt())
                        .apply();
                var ignoredLlmContext = LlmCallContext.open(request.workflowId(), request.agent())) {

            StructuredAssistant assistant = buildAssistant(request);
            String rawOutput = null;
            try {
                log.info("Agent {} starting (attempt {})", request.agent(), request.attempt());
                Result<String> result = assistant.chat(request.userPrompt());
                rawOutput = result.content();

                T parsed = parseWithRepair(assistant, request, rawOutput, outputType);

                execution.succeeded(
                        redactor.redactAndTruncate(rawOutput, MAX_AUDIT_CHARS),
                        redactor.redactAndTruncate(parser.writeAsJson(parsed), MAX_AUDIT_CHARS));
                executionRepository.save(execution);
                metrics.agentExecuted(request.agent(), model, execution.duration(), true);

                log.info(
                        "Agent {} finished successfully in {}ms with {} tool call(s)",
                        request.agent(),
                        execution.getDurationMs(),
                        result.toolExecutions() == null ? 0 : result.toolExecutions().size());
                return parsed;

            } catch (RuntimeException e) {
                execution.failed(e.toString(), redactor.redactAndTruncate(rawOutput, MAX_AUDIT_CHARS));
                executionRepository.save(execution);
                metrics.agentExecuted(request.agent(), model, execution.duration(), false);
                log.warn("Agent {} failed: {}", request.agent(), e.toString());
                throw new AgentExecutionException(request.agent(), "Agent " + request.agent() + " failed", e);
            }
        }
    }

    private <T> T parseWithRepair(
            StructuredAssistant assistant, AgentRequest request, String rawOutput, Class<T> outputType) {
        try {
            return parser.parse(rawOutput, outputType);
        } catch (LlmOutputParseException first) {
            // Self-hosted models occasionally answer with prose around the JSON. One targeted retry is
            // far cheaper than failing the whole ticket, but a second failure is a real problem and
            // must surface.
            log.info("Agent {} produced unparseable output, asking for a repair: {}", request.agent(), first.getMessage());
            String repairPrompt = request.userPrompt() + REPAIR_INSTRUCTION.formatted(first.getMessage());
            Result<String> repaired = assistant.chat(repairPrompt);
            return parser.parse(repaired.content(), outputType);
        }
    }

    private StructuredAssistant buildAssistant(AgentRequest request) {
        AiProperties.AgentSettings settings = aiProperties.settingsFor(request.agent());
        AiServices<StructuredAssistant> builder = AiServices.builder(StructuredAssistant.class)
                .chatModel(modelProvider.modelFor(request.agent()))
                .systemMessageProvider(ignored -> request.systemPrompt());

        if (!request.tools().isEmpty()) {
            builder = builder.tools(request.tools()).maxSequentialToolsInvocations(settings.maxToolCalls());
        }
        return builder.build();
    }
}
