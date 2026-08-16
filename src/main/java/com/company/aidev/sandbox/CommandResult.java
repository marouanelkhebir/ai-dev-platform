package com.company.aidev.sandbox;

import java.time.Duration;
import java.util.List;

/** Outcome of a command executed inside a sandbox. */
public record CommandResult(
        List<String> command, int exitCode, String stdout, String stderr, Duration duration, boolean timedOut) {

    public CommandResult {
        command = command == null ? List.of() : List.copyOf(command);
        stdout = stdout == null ? "" : stdout;
        stderr = stderr == null ? "" : stderr;
        duration = duration == null ? Duration.ZERO : duration;
    }

    public boolean successful() {
        return exitCode == 0 && !timedOut;
    }

    public String combinedOutput() {
        if (stderr.isBlank()) {
            return stdout;
        }
        if (stdout.isBlank()) {
            return stderr;
        }
        return stdout + "\n--- stderr ---\n" + stderr;
    }

    /** Compact rendering handed back to an agent as a tool result. */
    public String toToolOutput(int maxChars) {
        String body = combinedOutput();
        String header = "exitCode=" + exitCode + (timedOut ? " (TIMED OUT)" : "") + " durationMs="
                + duration.toMillis() + "\n";
        if (body.length() <= maxChars) {
            return header + body;
        }
        // Keep the head and the tail: Maven puts the useful failure summary at the end.
        int half = maxChars / 2;
        return header
                + body.substring(0, half)
                + "\n...[truncated " + (body.length() - maxChars) + " chars]...\n"
                + body.substring(body.length() - half);
    }
}
