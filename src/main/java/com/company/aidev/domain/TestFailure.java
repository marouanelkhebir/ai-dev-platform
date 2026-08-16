package com.company.aidev.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** A single failing test, extracted from the Maven output. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TestFailure(String testClass, String testMethod, String message, String stackTraceExcerpt) {

    public String displayName() {
        if (testMethod == null || testMethod.isBlank()) {
            return String.valueOf(testClass);
        }
        return testClass + "#" + testMethod;
    }
}
