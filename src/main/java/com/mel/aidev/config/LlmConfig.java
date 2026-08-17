package com.mel.aidev.config;

import com.mel.aidev.llm.LlmAuditListener;
import com.mel.aidev.llm.LlmExecutionRecorder;
import com.mel.aidev.observability.PlatformMetrics;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wiring of the LLM layer.
 *
 * <p>The audit listener is declared here rather than annotated as a component: it is an
 * infrastructure collaborator of the model builder, not a service, and keeping its construction
 * explicit makes it obvious that every model built by the provider carries it.
 */
@Configuration
public class LlmConfig {

    @Bean
    public LlmAuditListener llmAuditListener(PlatformMetrics metrics, LlmExecutionRecorder recorder) {
        return new LlmAuditListener(metrics, recorder);
    }
}
