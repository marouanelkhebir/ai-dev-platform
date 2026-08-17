package com.mel.aidev.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** A single remark raised by the reviewer agent. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ReviewFinding(
        Severity severity, String file, Integer line, String category, String description, String recommendation) {

    public ReviewFinding {
        severity = severity == null ? Severity.MINOR : severity;
    }

    public String toMarkdown() {
        String location = file == null ? "(general)" : (line == null ? file : file + ":" + line);
        return "- **" + severity + "** `" + location + "` — " + description
                + (recommendation == null || recommendation.isBlank() ? "" : "\n  - _Fix_: " + recommendation);
    }
}
