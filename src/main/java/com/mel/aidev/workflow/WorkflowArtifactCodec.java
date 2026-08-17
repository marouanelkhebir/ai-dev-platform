package com.mel.aidev.workflow;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Serialises the workflow artefacts stored on the workflow row.
 *
 * <p>Keeping the analysis, the plan and the reports as JSON on the workflow is what makes the engine
 * restartable: a step never depends on an in-memory object produced by a previous step.
 */
@Component
public class WorkflowArtifactCodec {

    private static final Logger log = LoggerFactory.getLogger(WorkflowArtifactCodec.class);

    private final ObjectMapper objectMapper;

    public WorkflowArtifactCodec(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String write(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(
                    "Unable to serialise workflow artefact " + value.getClass().getSimpleName(), e);
        }
    }

    public <T> Optional<T> read(String json, Class<T> type) {
        if (json == null || json.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(objectMapper.readValue(json, type));
        } catch (JsonProcessingException e) {
            // A stored artefact that no longer deserialises means the schema changed under an
            // in-flight workflow. Failing the read would strand the workflow forever, so it is
            // treated as absent and the step recomputes it.
            log.warn("Stored workflow artefact of type {} could not be read: {}", type.getSimpleName(), e.getOriginalMessage());
            return Optional.empty();
        }
    }

    public <T> T readRequired(String json, Class<T> type, String what) {
        return read(json, type)
                .orElseThrow(() -> new IllegalStateException("Workflow is missing its " + what + "; cannot continue"));
    }
}
