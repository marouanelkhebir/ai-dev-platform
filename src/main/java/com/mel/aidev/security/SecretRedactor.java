package com.mel.aidev.security;

import java.util.List;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Removes credentials from anything that leaves the process: logs, prompts, database audit rows and
 * merge request comments.
 *
 * <p>This is a defence in depth measure, not an excuse to put secrets where they do not belong.
 */
@Component
public class SecretRedactor {

    private static final String MASK = "***REDACTED***";

    private static final List<Pattern> PATTERNS = List.of(
            // GitLab / GitHub style tokens
            Pattern.compile("(glpat-|gldt-|glrt-|ghp_|github_pat_)[A-Za-z0-9_\\-]{16,}"),
            // Atlassian API tokens
            Pattern.compile("ATATT[A-Za-z0-9_\\-=]{16,}"),
            // Bearer / Basic authorization headers
            Pattern.compile("(?i)(authorization\\s*[:=]\\s*)(bearer|basic)\\s+[A-Za-z0-9._\\-+/=]+"),
            // key=value assignments of anything that smells like a credential
            Pattern.compile(
                    "(?i)((?:api[_-]?key|apikey|secret|password|passwd|token|private[_-]?key)\\s*[:=]\\s*)"
                            + "[\"']?[^\\s\"',;]{6,}[\"']?"),
            // Credentials embedded in a URL
            Pattern.compile("://[^/\\s:@]+:[^/\\s@]+@"),
            // JWT
            Pattern.compile("eyJ[A-Za-z0-9_\\-]{10,}\\.[A-Za-z0-9_\\-]{10,}\\.[A-Za-z0-9_\\-]{10,}"),
            // Private key blocks
            Pattern.compile("(?s)-----BEGIN [A-Z ]*PRIVATE KEY-----.*?-----END [A-Z ]*PRIVATE KEY-----"));

    public String redact(String input) {
        if (input == null || input.isBlank()) {
            return input;
        }
        String result = input;
        result = PATTERNS.get(0).matcher(result).replaceAll(MASK);
        result = PATTERNS.get(1).matcher(result).replaceAll(MASK);
        result = PATTERNS.get(2).matcher(result).replaceAll("$1" + MASK);
        result = PATTERNS.get(3).matcher(result).replaceAll("$1" + MASK);
        result = PATTERNS.get(4).matcher(result).replaceAll("://" + MASK + "@");
        result = PATTERNS.get(5).matcher(result).replaceAll(MASK);
        result = PATTERNS.get(6).matcher(result).replaceAll(MASK);
        return result;
    }

    /** Redacts and truncates, for audit columns and prompt echoes. */
    public String redactAndTruncate(String input, int maxChars) {
        String redacted = redact(input);
        if (redacted == null || redacted.length() <= maxChars) {
            return redacted;
        }
        return redacted.substring(0, maxChars) + "\n...[truncated " + (redacted.length() - maxChars) + " chars]";
    }
}
