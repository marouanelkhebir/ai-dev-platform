package com.mel.aidev.settings;

import java.time.Duration;
import java.time.format.DateTimeParseException;

/**
 * Editing type of a setting.
 *
 * <p>Drives both the widget rendered by the settings screen and the validation applied before a
 * value reaches the configuration binder — a temperature of {@code "hot"} must be rejected by the
 * API, not three hours later by an agent call.
 */
public enum SettingType {
    TEXT,
    URL,
    SECRET,
    INTEGER,
    DECIMAL,
    BOOLEAN,
    /** ISO-8601 duration, e.g. {@code PT30S}, {@code PT45M}, {@code P7D}. */
    DURATION,
    /** Comma-separated list bound to a {@code List<String>} property. */
    LIST,
    /** One of {@link SettingDefinition#options()}. */
    ENUM;

    /** True when the value must never be sent back to a client. */
    public boolean isSecret() {
        return this == SECRET;
    }

    /**
     * @return an error message, or {@code null} when the value is acceptable
     */
    public String validate(String value, SettingDefinition definition) {
        if (value == null || value.isBlank()) {
            return null; // blank means "no override"; the deployment default applies again
        }
        String trimmed = value.trim();
        return switch (this) {
            case INTEGER -> isLong(trimmed) ? null : "expected a whole number";
            case DECIMAL -> isDouble(trimmed) ? null : "expected a number";
            case BOOLEAN ->
                "true".equalsIgnoreCase(trimmed) || "false".equalsIgnoreCase(trimmed)
                        ? null
                        : "expected true or false";
            case DURATION -> isDuration(trimmed) ? null : "expected an ISO-8601 duration such as PT30S or P7D";
            case URL ->
                trimmed.startsWith("http://") || trimmed.startsWith("https://")
                        ? null
                        : "expected an URL starting with http:// or https://";
            case ENUM ->
                definition.options().stream().anyMatch(option -> option.equalsIgnoreCase(trimmed))
                        ? null
                        : "expected one of " + String.join(", ", definition.options());
            case TEXT, SECRET, LIST -> null;
        };
    }

    private static boolean isLong(String value) {
        try {
            Long.parseLong(value);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static boolean isDouble(String value) {
        try {
            Double.parseDouble(value);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static boolean isDuration(String value) {
        try {
            Duration.parse(value);
            return true;
        } catch (DateTimeParseException e) {
            return false;
        }
    }
}
