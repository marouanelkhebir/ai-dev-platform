package com.mel.aidev.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * A security issue. {@code scannerSource} distinguishes an interpretation of a scanner result (SAST,
 * dependency scanning, secret detection, SonarQube) from a finding raised by the agent itself.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SecurityFinding(
        Severity severity,
        String category,
        String file,
        Integer line,
        String description,
        String recommendation,
        String scannerSource,
        boolean falsePositive) {

    public SecurityFinding {
        severity = severity == null ? Severity.MINOR : severity;
        scannerSource = scannerSource == null || scannerSource.isBlank() ? "agent" : scannerSource;
    }

    public String toMarkdown() {
        String location = file == null ? "(general)" : (line == null ? file : file + ":" + line);
        return "- **" + severity + "** [" + category + "] `" + location + "` (" + scannerSource + ") — " + description
                + (recommendation == null || recommendation.isBlank() ? "" : "\n  - _Fix_: " + recommendation);
    }
}
