package com.company.aidev.settings;

import java.util.List;

/** Raised when a settings change is rejected before anything is written. */
public class SettingsValidationException extends RuntimeException {

    private final List<String> details;

    public SettingsValidationException(String message, List<String> details) {
        super(message);
        this.details = details == null ? List.of() : List.copyOf(details);
    }

    public List<String> getDetails() {
        return details;
    }
}
