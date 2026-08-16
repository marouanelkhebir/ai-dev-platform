package com.company.aidev.sandbox;

import com.company.aidev.config.SandboxProperties;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Validates every command before it reaches Docker.
 *
 * <p>Two rules make command injection structurally impossible:
 *
 * <ol>
 *   <li>commands are always {@code List<String>} passed straight to {@code execCreate}; no shell is
 *       ever spawned, so {@code ; rm -rf /} is an argument, not a separator;
 *   <li>the executable must belong to an allowlist, so even a fully compromised model cannot invoke
 *       {@code curl}, {@code ssh} or a downloaded binary.
 * </ol>
 */
@Component
public class CommandGuard {

    /** Arguments that would re-introduce a shell and defeat rule 1. */
    private static final Set<String> SHELL_EXECUTABLES = Set.of("sh", "bash", "zsh", "dash", "ksh", "env", "eval", "exec");

    /** Git subcommands the agents must never run. */
    private static final Set<String> FORBIDDEN_GIT_SUBCOMMANDS =
            Set.of("config", "remote", "credential", "filter-branch", "daemon", "submodule");

    private final SandboxProperties properties;

    public CommandGuard(SandboxProperties properties) {
        this.properties = properties;
    }

    /**
     * @throws SandboxException when the command is not allowed
     */
    public void validate(List<String> command) {
        if (command == null || command.isEmpty()) {
            throw new SandboxException("Empty command");
        }
        String executable = command.get(0);
        if (executable == null || executable.isBlank()) {
            throw new SandboxException("Blank executable");
        }
        if (SHELL_EXECUTABLES.contains(executable.toLowerCase(Locale.ROOT))) {
            throw new SandboxException("Spawning a shell is forbidden: " + executable);
        }
        if (!properties.allowedExecutables().contains(executable)) {
            throw new SandboxException(
                    "Executable not allowed: " + executable + " (allowed: " + properties.allowedExecutables() + ")");
        }
        for (String argument : command) {
            if (argument == null) {
                throw new SandboxException("Null argument in command " + command);
            }
            if (argument.indexOf('\0') >= 0) {
                throw new SandboxException("Null byte in command argument");
            }
        }
        if (isGit(executable) && command.size() > 1) {
            String subcommand = command.get(1).toLowerCase(Locale.ROOT);
            if (FORBIDDEN_GIT_SUBCOMMANDS.contains(subcommand)) {
                throw new SandboxException("git " + subcommand + " is forbidden for agents");
            }
        }
    }

    private static boolean isGit(String executable) {
        return "git".equals(executable);
    }
}
