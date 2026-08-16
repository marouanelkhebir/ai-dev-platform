package com.company.aidev.llm;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Turns a free-form model answer into a typed Java object.
 *
 * <p>Self-hosted models behind vLLM do not always honour {@code response_format}, and they routinely
 * wrap JSON in markdown fences or prepend a sentence of commentary. Rather than trusting the
 * gateway, the parser extracts the first balanced JSON value from the answer. This keeps the agents
 * working across different OpenAI-compatible model providers.
 */
@Component
public class StructuredOutputParser {

    private static final Logger log = LoggerFactory.getLogger(StructuredOutputParser.class);

    private final ObjectMapper objectMapper;

    public StructuredOutputParser() {
        this.objectMapper = JsonMapper.builder()
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.READ_UNKNOWN_ENUM_VALUES_AS_NULL)
                .enable(DeserializationFeature.ACCEPT_SINGLE_VALUE_AS_ARRAY)
                .build();
    }

    public <T> T parse(String rawOutput, Class<T> type) {
        if (rawOutput == null || rawOutput.isBlank()) {
            throw new LlmOutputParseException("Model returned an empty answer for " + type.getSimpleName(), rawOutput, null);
        }
        String json = extractJson(rawOutput);
        try {
            T parsed = objectMapper.readValue(json, type);
            if (parsed == null) {
                throw new LlmOutputParseException("Model returned JSON null for " + type.getSimpleName(), rawOutput, null);
            }
            return parsed;
        } catch (JsonProcessingException e) {
            log.debug("Failed to parse model answer as {}: {}", type.getSimpleName(), e.getOriginalMessage());
            throw new LlmOutputParseException(
                    "Model answer is not valid JSON for " + type.getSimpleName() + ": " + e.getOriginalMessage(),
                    rawOutput,
                    e);
        }
    }

    public String writeAsJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Unable to serialise " + value.getClass().getSimpleName(), e);
        }
    }

    public ObjectMapper objectMapper() {
        return objectMapper;
    }

    /**
     * Extracts the first balanced JSON object or array, ignoring braces that appear inside strings.
     * Handles the three shapes models actually produce: bare JSON, fenced JSON, and JSON preceded or
     * followed by prose.
     */
    String extractJson(String rawOutput) {
        String text = stripFences(rawOutput).trim();
        int start = firstIndexOfAny(text, '{', '[');
        if (start < 0) {
            throw new LlmOutputParseException("No JSON value found in model answer", rawOutput, null);
        }
        char open = text.charAt(start);
        char close = open == '{' ? '}' : ']';

        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == open) {
                depth++;
            } else if (c == close) {
                depth--;
                if (depth == 0) {
                    return text.substring(start, i + 1);
                }
            }
        }
        throw new LlmOutputParseException("Unbalanced JSON value in model answer", rawOutput, null);
    }

    private static String stripFences(String text) {
        String trimmed = text.trim();
        if (!trimmed.startsWith("```")) {
            return trimmed;
        }
        int firstNewline = trimmed.indexOf('\n');
        if (firstNewline < 0) {
            return trimmed;
        }
        String withoutOpening = trimmed.substring(firstNewline + 1);
        int closing = withoutOpening.lastIndexOf("```");
        return closing < 0 ? withoutOpening : withoutOpening.substring(0, closing);
    }

    private static int firstIndexOfAny(String text, char a, char b) {
        int indexA = text.indexOf(a);
        int indexB = text.indexOf(b);
        if (indexA < 0) {
            return indexB;
        }
        if (indexB < 0) {
            return indexA;
        }
        return Math.min(indexA, indexB);
    }
}
