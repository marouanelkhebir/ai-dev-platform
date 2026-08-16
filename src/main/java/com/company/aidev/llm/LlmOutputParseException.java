package com.company.aidev.llm;

/** Raised when an LLM answer cannot be turned into the expected structured type. */
public class LlmOutputParseException extends RuntimeException {

    private final String rawOutput;

    public LlmOutputParseException(String message, String rawOutput, Throwable cause) {
        super(message, cause);
        this.rawOutput = rawOutput;
    }

    public String getRawOutput() {
        return rawOutput;
    }
}
