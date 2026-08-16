package com.company.aidev.llm;

import com.company.aidev.persistence.entity.LlmExecutionEntity;
import com.company.aidev.persistence.repository.LlmExecutionRepository;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persists LLM call accounting in its own transaction.
 *
 * <p>{@code REQUIRES_NEW} matters here: an audit row must survive even when the surrounding business
 * transaction rolls back, and a failure to write the audit row must never break the call itself.
 */
@Component
public class LlmExecutionRecorder {

    private static final Logger log = LoggerFactory.getLogger(LlmExecutionRecorder.class);

    private final LlmExecutionRepository repository;

    public LlmExecutionRecorder(LlmExecutionRepository repository) {
        this.repository = repository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordSuccess(
            UUID workflowId,
            String agent,
            String model,
            long durationMs,
            Integer promptTokens,
            Integer completionTokens,
            Integer totalTokens,
            String finishReason) {
        save(LlmExecutionEntity.success(
                workflowId, agent, model, durationMs, promptTokens, completionTokens, totalTokens, finishReason));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFailure(UUID workflowId, String agent, String model, long durationMs, String error) {
        save(LlmExecutionEntity.failure(workflowId, agent, model, durationMs, error));
    }

    private void save(LlmExecutionEntity entity) {
        try {
            repository.save(entity);
        } catch (RuntimeException e) {
            log.warn("Unable to persist LLM execution audit row: {}", e.toString());
        }
    }
}
